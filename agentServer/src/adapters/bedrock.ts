/**
 * Amazon Bedrock adapter — TWO formats in one file:
 *
 *  1. Converse API (modern, unified): POST /model/{modelId}/converse
 *     - Works across all Bedrock models (Claude, Llama, Mistral, Titan, AI21)
 *     - Returns content blocks in a normalized shape
 *     - Streaming via /converse-stream with EventStream binary framing
 *
 *  2. InvokeModel (legacy, per-model): POST /model/{modelId}/invoke
 *     - Body shape differs by model (Anthropic-Claude vs Llama vs Mistral ...)
 *     - Streaming via /invoke-with-response-stream with chunked EventStream
 *
 * Both use AWS SigV4 auth. The `apiKeyRef` for a Bedrock provider is
 * "keyId:secretAccessKey:region" — parsed by sigv4.ts.
 *
 * For InvokeModel we focus on the Anthropic-Claude body shape (most
 * common) and a generic passthrough for everything else. Add more
 * per-model translators as needed.
 */
import type {
  AdapterResult,
  CanonicalChatMessage,
  CanonicalChatRequest,
  CanonicalToolDefinition,
  ProviderAdapter,
  ProviderConfig,
} from "../types.js";
import { parseBedrockApiKey, signRequest } from "./sigv4.js";

interface ConverseContentBlock {
  text?: string;
  toolUse?: { toolUseId: string; name: string; input: unknown };
  toolResult?: {
    toolUseId: string;
    content: Array<{ text: string }>;
    status?: "success" | "error";
  };
}

interface ConverseMessage {
  role: "user" | "assistant";
  content: ConverseContentBlock[];
}

interface ConverseRequest {
  messages: ConverseMessage[];
  system?: Array<{ text: string }>;
  toolConfig?: {
    tools: Array<{
      toolSpec: { name: string; description: string; inputSchema: { json: Record<string, unknown> } };
    }>;
  };
  inferenceConfig?: {
    maxTokens?: number;
    temperature?: number;
    topP?: number;
    stopSequences?: string[];
  };
}

interface ConverseResponse {
  output: { message: { role: "assistant"; content: ConverseContentBlock[] } };
  stopReason: string;
  usage?: { inputTokens: number; outputTokens: number; totalTokens: number };
}

export class BedrockAdapter implements ProviderAdapter {
  readonly protocol = "bedrock" as const;

  async execute(
    provider: ProviderConfig,
    request: CanonicalChatRequest,
    options: { apiKey: string; signal: AbortSignal }
  ): Promise<AdapterResult> {
    // Decision: Converse for any model not in the "invoke-only" set.
    // Converse supports Claude, Llama, Mistral, Titan, AI21, Cohere (on Bedrock).
    const useConverse = true;

    if (useConverse) {
      return this.converse(provider, request, options);
    }
    return this.invokeModel(provider, request, options);
  }

  // ---------- Converse ----------

  private async converse(
    provider: ProviderConfig,
    request: CanonicalChatRequest,
    options: { apiKey: string; signal: AbortSignal }
  ): Promise<AdapterResult> {
    const creds = parseBedrockApiKey(options.apiKey);
    const baseUrl = stripTrailingSlash(provider.baseUrl);
    const path = `/model/${encodeURIComponent(request.model)}/converse${request.stream ? "-stream" : ""}`;
    const url = new URL(baseUrl + path);
    const body = JSON.stringify(translateConverseRequest(request));
    const signed = signRequest(request.stream ? "POST" : "POST", url, body, creds, {
      "content-type": "application/json",
    });
    const res = await fetch(url, {
      method: "POST",
      headers: signed,
      body,
      signal: options.signal,
    });
    if (!res.ok) {
      const text = await res.text();
      throw new Error(`Bedrock Converse ${provider.id} returned ${res.status}: ${text}`);
    }

    if (request.stream) {
      if (!res.body) throw new Error(`Bedrock ${provider.id}: no streaming body`);
      return { kind: "stream", stream: streamEventStream(res.body) };
    }
    const json = (await res.json()) as unknown;
    return { kind: "json", body: translateConverseResponse(json) };
  }

  // ---------- InvokeModel (Anthropic-Claude body shape) ----------

  private async invokeModel(
    provider: ProviderConfig,
    request: CanonicalChatRequest,
    options: { apiKey: string; signal: AbortSignal }
  ): Promise<AdapterResult> {
    const creds = parseBedrockApiKey(options.apiKey);
    const baseUrl = stripTrailingSlash(provider.baseUrl);
    const path = `/model/${encodeURIComponent(request.model)}/${request.stream ? "invoke-with-response-stream" : "invoke"}`;
    const url = new URL(baseUrl + path);
    const body = JSON.stringify(translateInvokeRequestAnthropic(request));
    const signed = signRequest("POST", url, body, creds, { "content-type": "application/json" });
    const res = await fetch(url, {
      method: "POST",
      headers: signed,
      body,
      signal: options.signal,
    });
    if (!res.ok) {
      const text = await res.text();
      throw new Error(`Bedrock InvokeModel ${provider.id} returned ${res.status}: ${text}`);
    }
    if (request.stream) {
      if (!res.body) throw new Error(`Bedrock ${provider.id}: no streaming body`);
      return { kind: "stream", stream: streamEventStream(res.body) };
    }
    const json = (await res.json()) as unknown;
    return { kind: "json", body: translateInvokeResponseAnthropic(json) };
  }
}

// ---------- Converse: request translation ----------

function translateConverseRequest(req: CanonicalChatRequest): ConverseRequest {
  const out: ConverseRequest = { messages: [] };
  const systemParts: string[] = [];

  for (const m of req.messages) {
    if (m.role === "system") {
      if (typeof m.content === "string") systemParts.push(m.content);
      continue;
    }
    if (m.role === "user") {
      out.messages.push({
        role: "user",
        content: [{ text: typeof m.content === "string" ? m.content : "" }],
      });
      continue;
    }
    if (m.role === "assistant") {
      const blocks: ConverseContentBlock[] = [];
      if (m.content) blocks.push({ text: m.content });
      if (m.tool_calls) {
        for (const tc of m.tool_calls) {
          let input: unknown = {};
          try {
            input = JSON.parse(tc.function.arguments);
          } catch {
            input = {};
          }
          blocks.push({ toolUse: { toolUseId: tc.id, name: tc.function.name, input } });
        }
      }
      if (blocks.length > 0) out.messages.push({ role: "assistant", content: blocks });
      continue;
    }
    if (m.role === "tool") {
      out.messages.push({
        role: "user",
        content: [
          {
            toolResult: {
              toolUseId: m.tool_call_id ?? "",
              content: [{ text: typeof m.content === "string" ? m.content : "" }],
            },
          },
        ],
      });
    }
  }
  if (systemParts.length > 0) out.system = [{ text: systemParts.join("\n\n") }];
  if (req.tools?.length) {
    out.toolConfig = {
      tools: req.tools.map((t) => ({
        toolSpec: {
          name: t.function.name,
          description: t.function.description,
          inputSchema: { json: t.function.parameters },
        },
      })),
    };
  }
  const cfg: NonNullable<ConverseRequest["inferenceConfig"]> = {};
  if (req.max_tokens !== undefined) cfg.maxTokens = req.max_tokens;
  if (req.temperature !== undefined) cfg.temperature = req.temperature;
  if (req.top_p !== undefined) cfg.topP = req.top_p;
  if (req.stop) cfg.stopSequences = Array.isArray(req.stop) ? req.stop : [req.stop];
  if (Object.keys(cfg).length > 0) out.inferenceConfig = cfg;
  return out;
}

// ---------- Converse: response translation ----------

function translateConverseResponse(bedrock: unknown): unknown {
  const r = bedrock as ConverseResponse;
  const textParts: string[] = [];
  const toolCalls: Array<{
    id: string;
    type: "function";
    function: { name: string; arguments: string };
  }> = [];
  for (const block of r.output?.message?.content ?? []) {
    if (block.text) textParts.push(block.text);
    if (block.toolUse) {
      toolCalls.push({
        id: block.toolUse.toolUseId,
        type: "function",
        function: { name: block.toolUse.name, arguments: JSON.stringify(block.toolUse.input) },
      });
    }
  }
  return {
    id: "bedrock-" + Date.now(),
    object: "chat.completion",
    created: Math.floor(Date.now() / 1000),
    model: "bedrock",
    choices: [
      {
        index: 0,
        message: {
          role: "assistant",
          content: textParts.join("") || null,
          ...(toolCalls.length > 0 ? { tool_calls: toolCalls } : {}),
        },
        finish_reason: mapStopReason(r.stopReason),
      },
    ],
    usage: r.usage
      ? {
          prompt_tokens: r.usage.inputTokens,
          completion_tokens: r.usage.outputTokens,
          total_tokens: r.usage.totalTokens,
        }
      : undefined,
  };
}

function mapStopReason(r: string | undefined): "stop" | "tool_calls" | "length" | null {
  if (r === "end_turn" || r === "stop_sequence") return "stop";
  if (r === "tool_use") return "tool_calls";
  if (r === "max_tokens") return "length";
  return null;
}

// ---------- InvokeModel: Anthropic-Claude body shape ----------

interface AnthropicInvokeRequest {
  anthropic_version: "bedrock-2023-05-31";
  max_tokens: number;
  system?: string;
  messages: Array<{
    role: "user" | "assistant";
    content:
      | string
      | Array<
          | { type: "text"; text: string }
          | { type: "tool_use"; id: string; name: string; input: unknown }
          | { type: "tool_result"; tool_use_id: string; content: string }
        >;
  }>;
  tools?: Array<{
    name: string;
    description: string;
    input_schema: Record<string, unknown>;
  }>;
  temperature?: number;
  top_p?: number;
  stop_sequences?: string[];
}

function translateInvokeRequestAnthropic(req: CanonicalChatRequest): AnthropicInvokeRequest {
  const out: AnthropicInvokeRequest = {
    anthropic_version: "bedrock-2023-05-31",
    max_tokens: req.max_tokens ?? 4096,
    messages: [],
  };
  const systemParts: string[] = [];
  for (const m of req.messages) {
    if (m.role === "system") {
      if (typeof m.content === "string") systemParts.push(m.content);
      continue;
    }
    if (m.role === "user") {
      out.messages.push({ role: "user", content: typeof m.content === "string" ? m.content : "" });
      continue;
    }
    if (m.role === "assistant") {
      type InvokeBlock =
        | { type: "text"; text: string }
        | { type: "tool_use"; id: string; name: string; input: unknown }
        | { type: "tool_result"; tool_use_id: string; content: string };
      const blocks: InvokeBlock[] = [];
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
            content: typeof m.content === "string" ? m.content : "",
          },
        ],
      });
    }
  }
  if (systemParts.length > 0) out.system = systemParts.join("\n\n");
  if (req.tools?.length) {
    out.tools = req.tools.map((t) => ({
      name: t.function.name,
      description: t.function.description,
      input_schema: t.function.parameters,
    }));
  }
  if (req.temperature !== undefined) out.temperature = req.temperature;
  if (req.top_p !== undefined) out.top_p = req.top_p;
  if (req.stop) out.stop_sequences = Array.isArray(req.stop) ? req.stop : [req.stop];
  return out;
}

interface AnthropicInvokeResponse {
  id?: string;
  model?: string;
  stop_reason?: string;
  content: Array<{ type: "text"; text: string } | { type: "tool_use"; id: string; name: string; input: unknown }>;
  usage?: { input_tokens: number; output_tokens: number };
}

function translateInvokeResponseAnthropic(bedrock: unknown): unknown {
  const r = bedrock as AnthropicInvokeResponse;
  const textParts: string[] = [];
  const toolCalls: Array<{
    id: string;
    type: "function";
    function: { name: string; arguments: string };
  }> = [];
  for (const block of r.content ?? []) {
    if (block.type === "text") textParts.push(block.text);
    if (block.type === "tool_use") {
      toolCalls.push({
        id: block.id,
        type: "function",
        function: { name: block.name, arguments: JSON.stringify(block.input) },
      });
    }
  }
  return {
    id: r.id ?? "bedrock-" + Date.now(),
    object: "chat.completion",
    created: Math.floor(Date.now() / 1000),
    model: r.model ?? "bedrock",
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

// ---------- Converse streaming translation (EventStream binary) ----------
//
// Bedrock streaming responses use the AWS EventStream binary protocol:
// a series of "frames" each consisting of a prelude (12 bytes), headers,
// and a payload. We only care about the payload JSON for the messageStart,
// contentBlockDelta, messageStop events.

export function translateBedrockStreamChunk(raw: string): unknown[] {
  // raw is the JSON payload of a single EventStream frame.
  const trimmed = raw.trim();
  if (!trimmed) return [];
  let event: { messageStart?: unknown; contentBlockDelta?: unknown; contentBlockStop?: unknown; messageStop?: unknown; metadata?: unknown };
  try {
    event = JSON.parse(trimmed);
  } catch {
    return [];
  }
  const baseId = "bedrock-" + Date.now();
  const created = Math.floor(Date.now() / 1000);
  const out: unknown[] = [];

  // contentBlockDelta is the only event that carries incremental text.
  if (event.contentBlockDelta) {
    const delta = (event.contentBlockDelta as { delta?: { type?: string; text?: string } }).delta;
    if (delta?.type === "text_delta" && delta.text) {
      out.push({
        id: baseId,
        object: "chat.completion.chunk",
        created,
        model: "bedrock",
        choices: [{ index: 0, delta: { content: delta.text }, finish_reason: null }],
      });
    }
  }
  if (event.messageStop) {
    const stop = (event.messageStop as { stopReason?: string }).stopReason;
    out.push({
      id: baseId,
      object: "chat.completion.chunk",
      created,
      model: "bedrock",
      choices: [{ index: 0, delta: {}, finish_reason: mapStopReason(stop) }],
    });
    out.push("data: [DONE]\n\n");
  }
  return out;
}

// ---------- EventStream binary frame parser ----------

/**
 * EventStream prelude:
 *   4 bytes total length
 *   4 bytes total header length
 *   4 bytes prelude CRC
 * Followed by headers (each: nameLen 1, name, type 1, value), then payload.
 *
 * We don't validate CRCs (Bedrock returns well-formed frames); we just
 * read the length and extract the payload as a UTF-8 string, then yield
 * the parsed payload to the caller.
 */
async function* streamEventStream(
  body: ReadableStream<Uint8Array>
): AsyncGenerator<string, void, undefined> {
  const reader = body.getReader();
  let buffer = new Uint8Array(0);
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer = concatBytes(buffer, value);
      // Try to extract as many complete frames as possible.
      while (buffer.length >= 12) {
        const dv = new DataView(buffer.buffer, buffer.byteOffset, buffer.byteLength);
        const totalLen = dv.getUint32(0, false);
        if (totalLen < 12 || totalLen > buffer.length) break; // not enough bytes yet
        const payload = buffer.subarray(12, totalLen);
        // Copy into a fresh ArrayBuffer-backed Uint8Array to satisfy strict
        // typing (TS 5.7 disallows SharedArrayBuffer-backed views).
        const safePayload = new Uint8Array(payload.byteLength);
        safePayload.set(payload);
        yield new TextDecoder("utf-8").decode(safePayload);
        buffer = buffer.subarray(totalLen);
      }
    }
  } finally {
    reader.releaseLock();
  }
}

function concatBytes(a: Uint8Array, b: Uint8Array): Uint8Array<ArrayBuffer> {
  const out = new Uint8Array(new ArrayBuffer(a.length + b.length));
  out.set(a, 0);
  out.set(b, a.length);
  return out;
}

function stripTrailingSlash(s: string): string {
  return s.endsWith("/") ? s.slice(0, -1) : s;
}
