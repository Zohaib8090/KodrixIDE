/**
 * REST routes for provider management.
 * Mounts under /providers. Body shapes are deliberately simple — the
 * IDE doesn't need fancy filtering, just list/get/upsert/delete/test.
 */
import type { FastifyInstance } from "fastify";
import type { ProviderConfig, ProviderTestResult } from "../types.js";
import { resolveApiKey, type ProviderRegistry } from "../core/providers.js";
import { adapterFor } from "../adapters/registry.js";

export function registerProviderRoutes(app: FastifyInstance, registry: ProviderRegistry): void {
  // GET /providers — list all
  app.get("/providers", async () => {
    return { providers: registry.list() };
  });

  // GET /providers/:id — single provider
  app.get<{ Params: { id: string } }>("/providers/:id", async (req, reply) => {
    const p = registry.get(req.params.id);
    if (!p) return reply.code(404).send({ error: "Provider not found" });
    return p;
  });

  // POST /providers — create (or overwrite)
  app.post<{ Body: ProviderConfig }>("/providers", async (req, reply) => {
    try {
      await registry.upsert(req.body);
      return registry.get(req.body.id);
    } catch (err) {
      return reply.code(400).send({ error: (err as Error).message });
    }
  });

  // PATCH /providers/:id — partial update
  app.patch<{ Params: { id: string }; Body: Partial<ProviderConfig> }>(
    "/providers/:id",
    async (req, reply) => {
      const existing = registry.get(req.params.id);
      if (!existing) return reply.code(404).send({ error: "Provider not found" });
      try {
        const merged: ProviderConfig = { ...existing, ...req.body, id: existing.id };
        await registry.upsert(merged);
        return merged;
      } catch (err) {
        return reply.code(400).send({ error: (err as Error).message });
      }
    }
  );

  // DELETE /providers/:id
  app.delete<{ Params: { id: string } }>("/providers/:id", async (req, reply) => {
    const ok = await registry.remove(req.params.id);
    if (!ok) return reply.code(404).send({ error: "Provider not found" });
    return { ok: true };
  });

  // POST /providers/:id/test — verify connectivity and list available models
  app.post<{ Params: { id: string } }>("/providers/:id/test", async (req, reply) => {
    const provider = registry.get(req.params.id);
    if (!provider) return reply.code(404).send({ error: "Provider not found" });
    const result = await testProvider(provider);
    return result;
  });
}

async function testProvider(provider: ProviderConfig): Promise<ProviderTestResult> {
  const started = Date.now();
  try {
    const apiKey = resolveApiKey(provider.apiKeyRef);
    const adapter = adapterFor(provider.protocol);

    // Probe with a tiny non-streaming chat request. We need a model, so
    // fall back to "gpt-4o-mini" / "claude-haiku-4-5" / "gemini-2.5-flash"
    // depending on protocol if the provider has no models configured.
    const probeModel = pickProbeModel(provider);
    const result = await adapter.execute(
      provider,
      {
        model: probeModel,
        messages: [{ role: "user", content: "ping" }],
        max_tokens: 1,
        stream: false,
      },
      { apiKey, signal: AbortSignal.timeout(15_000) }
    );
    if (result.kind !== "json") {
      return { ok: false, error: "Provider returned a stream; expected non-streaming" };
    }
    return { ok: true, latencyMs: Date.now() - started, modelsAvailable: provider.models };
  } catch (err) {
    return { ok: false, error: (err as Error).message };
  }
}

function pickProbeModel(p: ProviderConfig): string {
  if (p.models.length > 0) return p.models[0]!;
  switch (p.protocol) {
    case "openai":
      return "gpt-4o-mini";
    case "anthropic":
      return "claude-haiku-4-5";
    case "gemini":
      return "gemini-2.5-flash";
    case "cohere":
      return "command-r-plus";
    case "bedrock":
      return "anthropic.claude-haiku-4-5-20251001-v1:0";
    case "vertex":
      return "gemini-2.5-flash";
  }
}
