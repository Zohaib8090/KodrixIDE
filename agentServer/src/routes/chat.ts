/**
 * Unified chat completions route. Accepts the canonical (OpenAI-shape)
 * request body and routes it to the right provider adapter.
 *
 * The IDE can also call this through /v1/chat/completions — the
 * canonical surface — to keep client code identical to direct OpenAI.
 */
import type { FastifyInstance, FastifyReply, FastifyRequest } from "fastify";
import type { CanonicalChatRequest, CanonicalChatResponse } from "../types.js";
import { resolveApiKey, type ProviderRegistry } from "../core/providers.js";
import { adapterFor, streamChunkTranslator } from "../adapters/registry.js";
import { prepareMessages, DEFAULT_MEMORY_OPTIONS } from "../core/memory.js";

export function registerChatRoutes(app: FastifyInstance, registry: ProviderRegistry): void {
  // The canonical OpenAI-compatible surface.
  app.post("/v1/chat/completions", async (req, reply) => {
    return handleChat(req, reply, registry);
  });

  // Same handler under a friendlier path for clients that prefer it.
  app.post("/chat", async (req, reply) => {
    return handleChat(req, reply, registry);
  });

  // GET /v1/models — OpenAI-compatible model listing.
  app.get("/v1/models", async () => {
    const models: Array<{ id: string; object: "model"; owned_by: string }> = [];
    for (const p of registry.listEnabled()) {
      for (const m of p.models) {
        models.push({ id: m, object: "model", owned_by: p.id });
      }
    }
    return { object: "list", data: models };
  });
}

async function handleChat(
  req: FastifyRequest,
  reply: FastifyReply,
  registry: ProviderRegistry
): Promise<unknown> {
  const body = req.body as Partial<CanonicalChatRequest> | undefined;
  if (!body || typeof body !== "object" || !body.model || !Array.isArray(body.messages)) {
    return reply.code(400).send({ error: "Invalid request: model and messages required" });
  }
  const request = body as CanonicalChatRequest;

  // 1. Resolve the provider. Explicit `provider` field wins, else by model id.
  const provider =
    (request.provider ? registry.get(request.provider) : undefined) ??
    registry.resolveForModel(request.model);
  if (!provider) {
    return reply
      .code(404)
      .send({ error: `No enabled provider found for model ${request.model}` });
  }
  if (!provider.enabled) {
    return reply.code(403).send({ error: `Provider ${provider.id} is disabled` });
  }

  // 2. Resolve API key.
  let apiKey: string;
  try {
    apiKey = resolveApiKey(provider.apiKeyRef);
  } catch (err) {
    return reply.code(500).send({ error: (err as Error).message });
  }

  // 3. Two-tier memory: optionally summarize older turns.
  const systemPrompt = extractSystemPrompt(request.messages);
  const prepared = await prepareMessages(
    request.messages,
    DEFAULT_MEMORY_OPTIONS,
    registry,
    systemPrompt
  );
  const finalRequest: CanonicalChatRequest = {
    ...request,
    messages: prepared.messages,
  };

  // 4. Dispatch to the provider adapter.
  const adapter = adapterFor(provider.protocol);
  const aborter = new AbortController();
  req.raw.on("close", () => aborter.abort());

  let result: Awaited<ReturnType<typeof adapter.execute>>;
  try {
    result = await adapter.execute(provider, finalRequest, { apiKey, signal: aborter.signal });
  } catch (err) {
    return reply.code(502).send({ error: (err as Error).message });
  }

  // 5. Return either JSON or a translated SSE stream.
  if (!request.stream) {
    if (result.kind !== "json") {
      return reply.code(500).send({ error: "Provider returned a stream; expected non-streaming" });
    }
    return result.body;
  }

  if (result.kind !== "stream") {
    return reply.code(500).send({ error: "Provider returned JSON; expected streaming" });
  }
  const translator = streamChunkTranslator(provider);
  reply.raw.setHeader("Content-Type", "text/event-stream; charset=utf-8");
  reply.raw.setHeader("Cache-Control", "no-cache, no-transform");
  reply.raw.setHeader("Connection", "keep-alive");
  reply.raw.setHeader("X-Accel-Buffering", "no");
  reply.raw.flushHeaders();
  try {
    for await (const raw of result.stream) {
      const chunks = translator(raw);
      for (const c of chunks) {
        if (typeof c === "string") {
          reply.raw.write(c);
        } else {
          reply.raw.write(`data: ${JSON.stringify(c)}\n\n`);
        }
      }
    }
    reply.raw.write("data: [DONE]\n\n");
  } catch (err) {
    reply.raw.write(
      `data: ${JSON.stringify({ error: (err as Error).message })}\n\n`
    );
  } finally {
    reply.raw.end();
  }
  // Returning reply from Fastify is a no-op once we've written to raw.
  return reply;
}

function extractSystemPrompt(messages: CanonicalChatRequest["messages"]): string | null {
  const parts: string[] = [];
  for (const m of messages) {
    if (m.role === "system" && typeof m.content === "string") parts.push(m.content);
  }
  if (parts.length === 0) return null;
  return parts.join("\n\n");
}

// Suppress unused warning for type-only import.
export type { CanonicalChatResponse };
