/**
 * Google Gemini adapter.
 *
 * Wire format is the third major shape:
 *   - endpoint is `/v1beta/models/{model}:generateContent` (or :streamGenerateContent)
 *   - body is `{ contents: [{ role, parts: [...] }], systemInstruction?, tools?, generationConfig }`
 *   - tool calls are { functionCall: { name, args } } inside parts
 *   - tool results are { functionResponse: { name, response } } inside a user turn
 *   - auth via `?key=APIKEY` query param (not Authorization header)
 */
import type {
  AdapterResult,
  CanonicalChatMessage,
  CanonicalChatRequest,
  ProviderAdapter,
  ProviderConfig,
} from "../types.js";

interface GeminiPart {
  text?: string;
  functionCall?: { name: string; args: Record<string, unknown> };
  functionResponse?: { name: string; response: Record<string, unknown> };
}

interface GeminiContent {
  role: "user" | "model";
  parts: GeminiPart[];
}

export interface GeminiRequest {
  contents: GeminiContent[];
  systemInstruction?: { parts: GeminiPart[] };
  tools?: Array<{
    functionDeclarations: Array<{
      name: string;
      description: string;
      parameters: Record<string, unknown>;
    }>;
  }>;
  toolConfig?: { functionCallingConfig?: { mode: "AUTO" | "ANY" | "NONE" } };
  generationConfig?: {
    temperature?: number;
    topP?: number;
    maxOutputTokens?: number;
    stopSequences?: string[];
  };
}

export class GeminiAdapter implements ProviderAdapter {
  readonly protocol = "gemini" as const;

  async execute(
    provider: ProviderConfig,
    request: CanonicalChatRequest,
    options: { apiKey: string; signal: AbortSignal }
  ): Promise<AdapterResult> {
    const base = stripTrailingSlash(provider.baseUrl);
    const action = request.stream ? "streamGenerateContent" : "generateContent";
    const url = `${base}/v1beta/models/${encodeURIComponent(request.model)}:${action}?key=${encodeURIComponent(options.apiKey)}`;

    const geminiReq = translateRequest(request);
    const res = await fetch(url, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(geminiReq),
      signal: options.signal,
    });

    if (!res.ok) {
      const text = await res.text();
      throw new Error(`Gemini provider ${provider.id} returned ${res.status}: ${text}`);
    }

    if (request.stream) {
      if (!res.body) throw new Error(`Provider ${provider.id}: no body on streaming response`);
      return { kind: "stream", stream: streamSse(res.body) };
    }

    const json = (await res.json()) as unknown;
    return { kind: "json", body: translateResponse(json) };
  }
}

// ---------- Request translation ----------

export function translateRequest(req: CanonicalChatRequest): GeminiRequest {
  const out: GeminiRequest = { contents: [] };

  for (const m of req.messages) {
    if (m.role === "system") {
      const text = typeof m.content === "string" ? m.content : "";
      if (text) out.systemInstruction = { parts: [{ text }] };
      continue;
    }
    if (m.role === "user") {
      out.contents.push({
        role: "user",
        parts: [{ text: typeof m.content === "string" ? m.content : "" }],
      });
      continue;
    }
    if (m.role === "assistant") {
      const parts: GeminiPart[] = [];
      if (m.content) parts.push({ text: m.content });
      if (m.tool_calls) {
        for (const tc of m.tool_calls) {
          let args: Record<string, unknown> = {};
          try {
            args = JSON.parse(tc.function.arguments);
          } catch {
            args = {};
          }
          parts.push({ functionCall: { name: tc.function.name, args } });
        }
      }
      if (parts.length > 0) out.contents.push({ role: "model", parts });
      continue;
    }
    if (m.role === "tool") {
      let response: Record<string, unknown> = {};
      try {
        response = JSON.parse(m.content ?? "{}");
      } catch {
        response = { result: m.content ?? "" };
      }
      const name = m.name ?? m.tool_call_id ?? "tool";
      out.contents.push({
        role: "user",
        parts: [{ functionResponse: { name, response } }],
      });
    }
  }

  if (req.tools?.length) {
    out.tools = [
      {
        functionDeclarations: req.tools.map((t) => ({
          name: t.function.name,
          description: t.function.description,
          parameters: t.function.parameters,
        })),
      },
    ];
  }

  if (req.tool_choice) {
    if (req.tool_choice === "auto") out.toolConfig = { functionCallingConfig: { mode: "AUTO" } };
    else if (req.tool_choice === "required") out.toolConfig = { functionCallingConfig: { mode: "ANY" } };
    else if (req.tool_choice === "none") out.toolConfig = { functionCallingConfig: { mode: "NONE" } };
  }

  const cfg: NonNullable<GeminiRequest["generationConfig"]> = {};
  if (req.temperature !== undefined) cfg.temperature = req.temperature;
  if (req.top_p !== undefined) cfg.topP = req.top_p;
  if (req.max_tokens !== undefined) cfg.maxOutputTokens = req.max_tokens;
  if (req.stop) cfg.stopSequences = Array.isArray(req.stop) ? req.stop : [req.stop];
  if (Object.keys(cfg).length > 0) out.generationConfig = cfg;

  return out;
}

// ---------- Response translation ----------

export function translateResponse(gemini: unknown): unknown {
  const r = gemini as {
    candidates?: Array<{
      content: { parts: GeminiPart[]; role: string };
      finishReason?: string;
    }>;
    modelVersion?: string;
    usageMetadata?: {
      promptTokenCount?: number;
      candidatesTokenCount?: number;
      totalTokenCount?: number;
    };
  };

  const cand = r.candidates?.[0];
  const textParts: string[] = [];
  const toolCalls: Array<{
    id: string;
    type: "function";
    function: { name: string; arguments: string };
  }> = [];
  if (cand) {
    for (const p of cand.content?.parts ?? []) {
      if (p.text) textParts.push(p.text);
      if (p.functionCall) {
        toolCalls.push({
          id: "gemini-" + Math.random().toString(36).slice(2, 10),
          type: "function",
          function: { name: p.functionCall.name, arguments: JSON.stringify(p.functionCall.args) },
        });
      }
    }
  }

  return {
    id: "gemini-" + Date.now(),
    object: "chat.completion",
    created: Math.floor(Date.now() / 1000),
    model: r.modelVersion ?? "gemini",
    choices: [
      {
        index: 0,
        message: {
          role: "assistant",
          content: textParts.join("") || null,
          ...(toolCalls.length > 0 ? { tool_calls: toolCalls } : {}),
        },
        finish_reason: mapFinishReason(cand?.finishReason),
      },
    ],
    usage: r.usageMetadata
      ? {
          prompt_tokens: r.usageMetadata.promptTokenCount ?? 0,
          completion_tokens: r.usageMetadata.candidatesTokenCount ?? 0,
          total_tokens: r.usageMetadata.totalTokenCount ?? 0,
        }
      : undefined,
  };
}

function mapFinishReason(r: string | undefined): "stop" | "tool_calls" | "length" | null {
  if (!r) return null;
  if (r === "STOP") return "stop";
  if (r === "MAX_TOKENS") return "length";
  return null;
}

// ---------- Streaming chunk translation ----------

/**
 * Gemini stream chunks are JSON objects (one per SSE data line) shaped like:
 *   { candidates: [{ content: { parts: [...] }, finishReason }], modelVersion, ... }
 */
export function translateGeminiStreamChunk(raw: string): unknown[] {
  const trimmed = raw.trim();
  if (!trimmed.startsWith("data:")) return [];
  const payload = trimmed.slice(5).trim();
  if (!payload) return [];
  let event: {
    candidates?: Array<{ content: { parts: GeminiPart[] }; finishReason?: string }>;
    modelVersion?: string;
  };
  try {
    event = JSON.parse(payload);
  } catch {
    return [];
  }
  const cand = event.candidates?.[0];
  if (!cand) return [];

  const baseId = "gemini-" + Date.now();
  const baseModel = event.modelVersion ?? "gemini";
  const created = Math.floor(Date.now() / 1000);

  const out: unknown[] = [];
  for (const p of cand.content?.parts ?? []) {
    if (p.text) {
      out.push({
        id: baseId,
        object: "chat.completion.chunk",
        created,
        model: baseModel,
        choices: [{ index: 0, delta: { content: p.text }, finish_reason: null }],
      });
    }
  }
  if (cand.finishReason) {
    out.push({
      id: baseId,
      object: "chat.completion.chunk",
      created,
      model: baseModel,
      choices: [{ index: 0, delta: {}, finish_reason: mapFinishReason(cand.finishReason) }],
    });
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
