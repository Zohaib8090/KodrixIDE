/**
 * OpenAI-shape adapter.
 *
 * Covers: OpenAI, DeepSeek, Ollama (with OpenAI-compat endpoint),
 * LM Studio, vLLM, Together, Groq, and any other OpenAI-compatible API.
 *
 * The wire format is the canonical format, so request translation is
 * mostly pass-through. Tool-calling, system messages, and the `tools`
 * array all use the same names — that is the entire point of OpenAI
 * compatibility across the industry.
 */
import type {
  AdapterResult,
  CanonicalChatRequest,
  ProviderAdapter,
  ProviderConfig,
} from "../types.js";

export class OpenAIAdapter implements ProviderAdapter {
  readonly protocol = "openai" as const;

  async execute(
    provider: ProviderConfig,
    request: CanonicalChatRequest,
    options: { apiKey: string; signal: AbortSignal }
  ): Promise<AdapterResult> {
    const url = `${stripTrailingSlash(provider.baseUrl)}/v1/chat/completions`;
    const headers: Record<string, string> = {
      "Content-Type": "application/json",
      Authorization: `Bearer ${options.apiKey}`,
    };
    const body = JSON.stringify(request);

    const res = await fetch(url, {
      method: "POST",
      headers,
      body,
      signal: options.signal,
    });

    if (!res.ok) {
      // Surface the provider's error body verbatim — it's the most
      // useful thing to show the user when the request fails.
      const text = await res.text();
      throw new Error(`OpenAI-compat provider ${provider.id} returned ${res.status}: ${text}`);
    }

    if (request.stream) {
      if (!res.body) throw new Error(`Provider ${provider.id}: no body on streaming response`);
      return {
        kind: "stream",
        stream: streamSse(res.body),
      };
    }

    const json = (await res.json()) as unknown;
    return { kind: "json", body: json };
  }
}

/**
 * OpenAI-format streaming IS the canonical format, so the translator
 * is a 1:1 pass-through after parsing the SSE envelope. The server's
 * chat-completion route handler validates the resulting chunk against
 * the canonical schema before forwarding it to the client.
 */
export function translateOpenAIChunk(raw: string): unknown | null {
  const trimmed = raw.trim();
  if (!trimmed.startsWith("data:")) return null;
  const payload = trimmed.slice(5).trim();
  if (payload === "[DONE]") return null;
  try {
    return JSON.parse(payload);
  } catch {
    return null;
  }
}

// ---- helpers ----

function stripTrailingSlash(s: string): string {
  return s.endsWith("/") ? s.slice(0, -1) : s;
}

/**
 * Convert a Web ReadableStream to an AsyncIterable of SSE data payloads.
 * Each yielded item is the full data line, e.g. "data: {...}\n\n".
 *
 * Why a hand-rolled parser instead of an EventSource client: EventSource
 * is browser-only. The server-side equivalent needs us to parse the
 * framing ourselves. The OpenAI/Anthropic/Gemini SSE format is just
 * "data: <json>\n\n" separated by blank lines, so a minimal parser is
 * enough and avoids yet another dependency.
 */
async function* streamSse(
  body: ReadableStream<Uint8Array>
): AsyncGenerator<string, void, undefined> {
  const reader = body.getReader();
  const decoder = new TextDecoder("utf-8");
  let buffer = "";

  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });

      // Split on the SSE message boundary (blank line).
      let idx: number;
      while ((idx = buffer.indexOf("\n\n")) !== -1) {
        const message = buffer.slice(0, idx);
        buffer = buffer.slice(idx + 2);
        if (message.trim()) yield message;
      }
    }
    // Flush any trailing data not terminated by blank line.
    if (buffer.trim()) yield buffer;
  } finally {
    reader.releaseLock();
  }
}
