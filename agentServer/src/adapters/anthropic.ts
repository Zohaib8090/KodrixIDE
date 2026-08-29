/**
 * Anthropic Messages API adapter.
 *
 * Wire format differs from the canonical OpenAI shape in three key ways:
 *   1. system messages are a top-level `system` field, not a message
 *   2. assistant tool calls are `content: [{ type: "tool_use", id, name, input }]`
 *   3. tool results are `role: "user", content: [{ type: "tool_result", tool_use_id, content }]`
 *
 * The translate* functions below are the ONLY place this conversion lives.
 * The OpenAI adapter is unchanged.
 */
import type {
  AdapterResult,
  CanonicalChatMessage,
  CanonicalChatRequest,
  CanonicalToolDefinition,
  ProviderAdapter,
  ProviderConfig,
} from "../types.js";

interface AnthropicRequest {
  model: string;
  max_tokens: number;
  system?: string;
  messages: Array<{
    role: "user" | "assistant";
    content: AnthropicContentBlock[];
  }>;
  tools?: Array<{
    name: string;
    description: string;
    input_schema: Record<string, unknown>;
  }>;
  tool_choice?: { type: "auto" | "any" | "tool"; name?: string };
  temperature?: number;
  top_p?: number;
  stop_sequences?: string[];
  stream?: boolean;
}

type AnthropicContentBlock =
  | { type: "text"; text: string }
  | { type: "tool_use"; id: string; name: string; input: unknown }
  | { type: "tool_result"; tool_use_id: string; content: string | AnthropicContentBlock[]; is_error?: boolean };

export class AnthropicAdapter implements ProviderAdapter {
  readonly protocol = "anthropic" as const;

  async execute(
    provider: ProviderConfig,
    request: CanonicalChatRequest,
    options: { apiKey: string; signal: AbortSignal }
  ): Promise<AdapterResult> {
    const url = `${stripTrailingSlash(provider.baseUrl)}/v1/messages`;
    const headers: Record<string, string> = {
      "Content-Type": "application/json",
      "x-api-key": options.apiKey,
      "anthropic-version": "2023-06-01",
    };
    const body = JSON.stringify(translateRequest(request));

    const res = await fetch(url, {
      method: "POST",
      headers,
      body,
      signal: options.signal,
    });

    if (!res.ok) {
      const text = await res.text();
      throw new Error(`Anthropic provider ${provider.id} returned ${res.status}: ${text}`);
    }

    if (request.stream) {
      if (!res.body) throw new Error(`Provider ${provider.id}: no body on streaming response`);
      return { kind: "stream", stream: streamSse(res.body) };
    }

    const json = (await res.json()) as unknown;
    return { kind: "json", body: translateResponse(json) };
  }
}

// ---------- Request translation: canonical -> Anthropic ----------

function translateRequest(req: CanonicalChatRequest): AnthropicRequest {
  const out: AnthropicRequest = {
    model: req.model,
    max_tokens: req.max_tokens ?? 4096,
    messages: [],
  };

  // System message -> top-level field. Concatenate if there are several.
  const systemParts: string[] = [];
  for (const m of req.messages) {
    if (m.role === "system") {
      if (typeof m.content === "string") systemParts.push(m.content);
    }
  }
  if (systemParts.length > 0) out.system = systemParts.join("\n\n");

  for (const m of req.messages) {
    if (m.role === "system") continue;
    if (m.role === "user") {
      out.messages.push({ role: "user", content: [textOrToolResult(m)] });
      continue;
    }
    if (m.role === "assistant") {
      const blocks: AnthropicContentBlock[] = [];
      if (m.content) blocks.push({ type: "text", text: m.content });
      if (m.tool_calls) {
        for (const tc of m.tool_calls) {
          let input: unknown = {};
          try {
            input = JSON.parse(tc.function.arguments);
          } catch {
            input = {};
          }
          blocks.push({ type: "tool_use", id: tc.id, name: tc.function.name, input });
        }
      }
      out.messages.push({ role: "assistant", content: blocks });
      continue;
    }
    if (m.role === "tool") {
      out.messages.push({
        role: "user",
        content: [
          {
            type: "tool_result",
            tool_use_id: m.tool_call_id ?? "",
            content: m.content ?? "",
          },
        ],
      });
    }
  }

  if (req.tools?.length) {
    out.tools = req.tools.map((t) => ({
      name: t.function.name,
      description: t.function.description,
      input_schema: t.function.parameters,
    }));
  }

  if (req.tool_choice) {
    if (req.tool_choice === "auto") out.tool_choice = { type: "auto" };
    else if (req.tool_choice === "required") out.tool_choice = { type: "any" };
    else if (req.tool_choice === "none") {
      // Anthropic has no explicit "none" — omit tools instead.
      out.tools = undefined;
    } else {
      out.tool_choice = { type: "tool", name: req.tool_choice.function.name };
    }
  }

  if (req.temperature !== undefined) out.temperature = req.temperature;
  if (req.top_p !== undefined) out.top_p = req.top_p;
  if (req.stop) {
    out.stop_sequences = Array.isArray(req.stop) ? req.stop : [req.stop];
  }
  out.stream = !!req.stream;

  return out;
}

function textOrToolResult(m: CanonicalChatMessage): AnthropicContentBlock {
  return { type: "text", text: typeof m.content === "string" ? m.content : "" };
}

// ---------- Response translation: Anthropic -> canonical ----------

function translateResponse(anthropic: unknown): unknown {
  const r = anthropic as {
    id: string;
    model: string;
    content: AnthropicContentBlock[];
    stop_reason: string | null;
    usage?: { input_tokens: number; output_tokens: number };
  };
  const textParts: string[] = [];
  const toolCalls: Array<{
    id: string;
    type: "function";
    function: { name: string; arguments: string };
  }> = [];

  for (const block of r.content ?? []) {
    if (block.type === "text") textParts.push(block.text);
    else if (block.type === "tool_use") {
      toolCalls.push({
        id: block.id,
        type: "function",
        function: { name: block.name, arguments: JSON.stringify(block.input) },
      });
    }
  }

  return {
    id: r.id,
    object: "chat.completion",
    created: Math.floor(Date.now() / 1000),
    model: r.model,
    choices: [
      {
        index: 0,
        message: {
          role: "assistant",
          content: textParts.join("") || null,
          ...(toolCalls.length > 0 ? { tool_calls: toolCalls } : {}),
        },
        finish_reason: mapStopReason(r.stop_reason),
      },
    ],
    usage: r.usage
      ? {
          prompt_tokens: r.usage.input_tokens,
          completion_tokens: r.usage.output_tokens,
          total_tokens: r.usage.input_tokens + r.usage.output_tokens,
        }
      : undefined,
  };
}

function mapStopReason(
  r: string | null
): "stop" | "tool_calls" | "length" | "content_filter" | null {
  if (r === "end_turn" || r === "stop_sequence") return "stop";
  if (r === "tool_use") return "tool_calls";
  if (r === "max_tokens") return "length";
  return null;
}

// ---------- Streaming chunk translation: Anthropic SSE -> canonical ----------

/**
 * Anthropic SSE events of interest:
 *   message_start, content_block_start, content_block_delta,
 *   content_block_stop, message_delta, message_stop, ping, error
 *
 * We translate content_block_delta (text_delta, input_json_delta) into
 * OpenAI-format streaming chunks so the client gets a single uniform stream.
 */
export function translateAnthropicStreamChunk(raw: string): unknown[] {
  const trimmed = raw.trim();
  if (!trimmed.startsWith("data:")) return [];
  const payload = trimmed.slice(5).trim();
  if (!payload) return [];

  let event: { type: string; [k: string]: unknown };
  try {
    event = JSON.parse(payload);
  } catch {
    return [];
  }

  const out: unknown[] = [];
  const baseId = (event["message"] as { id?: string } | undefined)?.id ?? "anthropic-" + Date.now();
  const baseModel = (event["message"] as { model?: string } | undefined)?.model ?? "unknown";
  const created = Math.floor(Date.now() / 1000);

  switch (event.type) {
    case "message_start":
      out.push({
        id: baseId,
        object: "chat.completion.chunk",
        created,
        model: baseModel,
        choices: [{ index: 0, delta: { role: "assistant", content: "" }, finish_reason: null }],
      });
      break;
    case "content_block_delta": {
      const delta = event["delta"] as { type: string; text?: string; partial_json?: string } | undefined;
      if (delta?.type === "text_delta" && delta.text) {
        out.push({
          id: baseId,
          object: "chat.completion.chunk",
          created,
          model: baseModel,
          choices: [{ index: 0, delta: { content: delta.text }, finish_reason: null }],
        });
      } else if (delta?.type === "input_json_delta" && delta.partial_json) {
        // Tool-call input deltas: surface as plain text content. Clients
        // that need full streaming tool calls can request a more detailed
        // format in a later version.
        out.push({
          id: baseId,
          object: "chat.completion.chunk",
          created,
          model: baseModel,
          choices: [{ index: 0, delta: { content: delta.partial_json }, finish_reason: null }],
        });
      }
      break;
    }
    case "message_delta": {
      const stop = (event["delta"] as { stop_reason?: string } | undefined)?.stop_reason;
      out.push({
        id: baseId,
        object: "chat.completion.chunk",
        created,
        model: baseModel,
        choices: [{ index: 0, delta: {}, finish_reason: mapStopReason(stop ?? null) }],
      });
      break;
    }
    case "message_stop":
      out.push("data: [DONE]\n\n");
      break;
    default:
      break;
  }
  return out;
}

// ---------- helpers ----------

function stripTrailingSlash(s: string): string {
  return s.endsWith("/") ? s.slice(0, -1) : s;
}

async function* streamSse(body: ReadableStream<Uint8Array>): AsyncGenerator<string, void, undefined> {
  const reader = body.getReader();
  const decoder = new TextDecoder("utf-8");
  let buffer = "";
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      let idx: number;
      while ((idx = buffer.indexOf("\n\n")) !== -1) {
        const message = buffer.slice(0, idx);
        buffer = buffer.slice(idx + 2);
        if (message.trim()) yield message;
      }
    }
    if (buffer.trim()) yield buffer;
  } finally {
    reader.releaseLock();
  }
}
