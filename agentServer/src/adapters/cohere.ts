/**
 * Cohere adapter (Command R+ style).
 *
 * Wire format: https://docs.cohere.com/reference/chat
 *   - Endpoint: POST {baseUrl}/v1/chat
 *   - Auth: Authorization: Bearer <key>
 *   - Body: { model, message, preamble?, chat_history?, tools?, temperature, ... }
 *   - Response: { text, tool_calls, finish_reason, ... }
 *   - Streaming: NDJSON (not SSE) — each line is a JSON event.
 */
import type {
  AdapterResult,
  CanonicalChatMessage,
  CanonicalChatRequest,
  CanonicalToolDefinition,
  ProviderAdapter,
  ProviderConfig,
} from "../types.js";

interface CohereTool {
  type: "function";
  function: {
    name: string;
    description: string;
    parameters: Record<string, unknown>;
  };
}

interface CohereRequest {
  model: string;
  message: string;
  preamble?: string;
  chat_history?: Array<{ role: "USER" | "CHATBOT"; message: string; tool_calls?: CohereToolCall[] }>;
  tools?: CohereTool[];
  tool_choice?: "AUTO" | "NONE" | "REQUIRED";
  temperature?: number;
  p?: number;
  max_tokens?: number;
  stop_sequences?: string[];
  stream?: boolean;
}

interface CohereToolCall {
  name: string;
  parameters: Record<string, unknown>;
}

interface CohereContent {
  type: "text" | "tool_call" | "tool_result";
  text?: string;
  tool_call?: CohereToolCall;
}

interface CohereResponse {
  id?: string;
  message: { role: "assistant"; content: CohereContent[] };
  finish_reason: string;
  usage?: {
    billed_units?: { input_tokens?: number; output_tokens?: number };
    tokens?: { input_tokens?: number; output_tokens?: number };
  };
}

export class CohereAdapter implements ProviderAdapter {
  readonly protocol = "cohere" as const;

  async execute(
    provider: ProviderConfig,
    request: CanonicalChatRequest,
    options: { apiKey: string; signal: AbortSignal }
  ): Promise<AdapterResult> {
    const url = `${stripTrailingSlash(provider.baseUrl)}/v1/chat`;
    const headers: Record<string, string> = {
      "Content-Type": "application/json",
      Authorization: `Bearer ${options.apiKey}`,
    };
    const cohereReq = translateRequest(request);
    const res = await fetch(url, {
      method: "POST",
      headers,
      body: JSON.stringify(cohereReq),
      signal: options.signal,
    });

    if (!res.ok) {
      const text = await res.text();
      throw new Error(`Cohere provider ${provider.id} returned ${res.status}: ${text}`);
    }

    if (request.stream) {
      if (!res.body) throw new Error(`Provider ${provider.id}: no body on streaming response`);
      return { kind: "stream", stream: streamNdjson(res.body) };
    }

    const json = (await res.json()) as unknown;
    return { kind: "json", body: translateResponse(json) };
  }
}

// ---------- Request translation ----------

function translateRequest(req: CanonicalChatRequest): CohereRequest {
  const out: CohereRequest = { model: req.model, message: "" };
  const history: NonNullable<CohereRequest["chat_history"]> = [];
  let lastUserMessage = "";

  for (const m of req.messages) {
    if (m.role === "system") {
      if (typeof m.content === "string") {
        // Cohere v2 only has one preamble; concat if multiple.
        out.preamble = out.preamble ? `${out.preamble}\n\n${m.content}` : m.content;
      }
      continue;
    }
    if (m.role === "user") {
      const text = typeof m.content === "string" ? m.content : "";
      // Cohere wants the latest user turn in `message` and earlier ones in `chat_history`.
      lastUserMessage = text;
      // Don't push yet — we'll set `message` to this and keep history empty for now.
      continue;
    }
    if (m.role === "assistant") {
      history.push({
        role: "CHATBOT",
        message: typeof m.content === "string" ? m.content : "",
        ...(m.tool_calls && m.tool_calls.length > 0
          ? {
              tool_calls: m.tool_calls.map((tc) => ({
                name: tc.function.name,
                parameters: safeJson(tc.function.arguments),
              })),
            }
          : {}),
      });
      continue;
    }
    if (m.role === "tool") {
      // Tool results become a USER turn with a tool_result content block.
      // Cohere's tool_result format is name + output; we fold name from `name` if present.
      history.push({
        role: "USER",
        message: typeof m.content === "string" ? m.content : "",
      });
    }
  }

  out.message = lastUserMessage;
  if (history.length > 0) out.chat_history = history;

  if (req.tools?.length) {
    out.tools = req.tools.map(cohereTool);
  }
  if (req.tool_choice) {
    if (req.tool_choice === "auto") out.tool_choice = "AUTO";
    else if (req.tool_choice === "none") out.tool_choice = "NONE";
    else if (req.tool_choice === "required") out.tool_choice = "REQUIRED";
  }
  if (req.temperature !== undefined) out.temperature = req.temperature;
  if (req.top_p !== undefined) out.p = req.top_p;
  if (req.max_tokens !== undefined) out.max_tokens = req.max_tokens;
  if (req.stop) out.stop_sequences = Array.isArray(req.stop) ? req.stop : [req.stop];
  out.stream = !!req.stream;

  return out;
}

function cohereTool(t: CanonicalToolDefinition): CohereTool {
  return {
    type: "function",
    function: {
      name: t.function.name,
      description: t.function.description,
      parameters: t.function.parameters,
    },
  };
}

function safeJson(s: string): Record<string, unknown> {
  try {
    return JSON.parse(s);
  } catch {
    return {};
  }
}

// ---------- Response translation ----------

function translateResponse(cohere: unknown): unknown {
  const r = cohere as CohereResponse;
  const textParts: string[] = [];
  const toolCalls: Array<{
    id: string;
    type: "function";
    function: { name: string; arguments: string };
  }> = [];

  for (const block of r.message?.content ?? []) {
    if (block.type === "text" && block.text) textParts.push(block.text);
    if (block.type === "tool_call" && block.tool_call) {
      toolCalls.push({
        id: "cohere-" + Math.random().toString(36).slice(2, 10),
        type: "function",
        function: { name: block.tool_call.name, arguments: JSON.stringify(block.tool_call.parameters) },
      });
    }
  }

  return {
    id: r.id ?? "cohere-" + Date.now(),
    object: "chat.completion",
    created: Math.floor(Date.now() / 1000),
    model: "cohere",
    choices: [
      {
        index: 0,
        message: {
          role: "assistant",
          content: textParts.join("") || null,
          ...(toolCalls.length > 0 ? { tool_calls: toolCalls } : {}),
        },
        finish_reason: mapFinishReason(r.finish_reason),
      },
    ],
    usage: r.usage
      ? {
          prompt_tokens: r.usage.tokens?.input_tokens ?? r.usage.billed_units?.input_tokens ?? 0,
          completion_tokens: r.usage.tokens?.output_tokens ?? r.usage.billed_units?.output_tokens ?? 0,
          total_tokens:
            (r.usage.tokens?.input_tokens ?? r.usage.billed_units?.input_tokens ?? 0) +
            (r.usage.tokens?.output_tokens ?? r.usage.billed_units?.output_tokens ?? 0),
        }
      : undefined,
  };
}

function mapFinishReason(r: string): "stop" | "tool_calls" | "length" | "content_filter" | null {
  if (r === "COMPLETE" || r === "STOP") return "stop";
  if (r === "TOOL_CALL") return "tool_calls";
  if (r === "MAX_TOKENS") return "length";
  if (r === "ERROR" || r === "ERROR_TOXIC") return "content_filter";
  return null;
}

// ---------- Streaming translation (NDJSON) ----------

interface CohereStreamEvent {
  event_type: string;
  text?: string;
  tool_calls?: CohereToolCall[];
  finish_reason?: string;
  is_finished?: boolean;
  response?: CohereResponse;
}

export function translateCohereStreamChunk(raw: string): unknown[] {
  const trimmed = raw.trim();
  if (!trimmed) return [];
  let event: CohereStreamEvent;
  try {
    event = JSON.parse(trimmed);
  } catch {
    return [];
  }
  const baseId = "cohere-" + Date.now();
  const created = Math.floor(Date.now() / 1000);
  const out: unknown[] = [];

  switch (event.event_type) {
    case "stream-start":
      out.push({
        id: baseId,
        object: "chat.completion.chunk",
        created,
        model: "cohere",
        choices: [{ index: 0, delta: { role: "assistant", content: "" }, finish_reason: null }],
      });
      break;
    case "text-generation":
      if (event.text) {
        out.push({
          id: baseId,
          object: "chat.completion.chunk",
          created,
          model: "cohere",
          choices: [{ index: 0, delta: { content: event.text }, finish_reason: null }],
        });
      }
      break;
    case "tool-calls-generation":
      if (event.tool_calls) {
        for (const tc of event.tool_calls) {
          out.push({
            id: baseId,
            object: "chat.completion.chunk",
            created,
            model: "cohere",
            choices: [
              {
                index: 0,
                delta: {
                  tool_calls: [
                    {
                      id: "cohere-" + Math.random().toString(36).slice(2, 10),
                      type: "function",
                      function: { name: tc.name, arguments: JSON.stringify(tc.parameters) },
                    },
                  ],
                },
                finish_reason: null,
              },
            ],
          });
        }
      }
      break;
    case "stream-end":
      if (event.finish_reason) {
        out.push({
          id: baseId,
          object: "chat.completion.chunk",
          created,
          model: "cohere",
          choices: [{ index: 0, delta: {}, finish_reason: mapFinishReason(event.finish_reason) }],
        });
      }
      out.push("data: [DONE]\n\n");
      break;
  }
  return out;
}

// ---------- helpers ----------

function stripTrailingSlash(s: string): string {
  return s.endsWith("/") ? s.slice(0, -1) : s;
}

async function* streamNdjson(body: ReadableStream<Uint8Array>): AsyncGenerator<string, void, undefined> {
  const reader = body.getReader();
  const decoder = new TextDecoder("utf-8");
  let buffer = "";
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      let idx: number;
      // NDJSON: split on newline.
      while ((idx = buffer.indexOf("\n")) !== -1) {
        const line = buffer.slice(0, idx).trim();
        buffer = buffer.slice(idx + 1);
        if (line) yield line;
      }
    }
    if (buffer.trim()) yield buffer.trim();
  } finally {
    reader.releaseLock();
  }
}
