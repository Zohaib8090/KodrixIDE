/**
 * Maps a ProviderProtocol to its concrete adapter instance.
 *
 * Each protocol has exactly one adapter here. If we ever need two
 * different OpenAI-shape adapters (e.g. one for OpenAI and one for
 * Azure OpenAI), add a `protocol_variant` field to ProviderConfig and
 * branch on that here — do NOT try to overload the OpenAI adapter.
 */
import type { ProviderAdapter, ProviderConfig, ProviderProtocol } from "../types.js";
import { OpenAIAdapter } from "./openai.js";
import { AnthropicAdapter, translateAnthropicStreamChunk } from "./anthropic.js";
import { GeminiAdapter, translateGeminiStreamChunk } from "./gemini.js";
import { CohereAdapter, translateCohereStreamChunk } from "./cohere.js";
import { BedrockAdapter, translateBedrockStreamChunk } from "./bedrock.js";
import { VertexAdapter, translateVertexStreamChunk } from "./vertex.js";
import { translateOpenAIChunk } from "./openai.js";

const ADAPTERS: Record<ProviderProtocol, ProviderAdapter> = {
  openai: new OpenAIAdapter(),
  anthropic: new AnthropicAdapter(),
  gemini: new GeminiAdapter(),
  cohere: new CohereAdapter(),
  bedrock: new BedrockAdapter(),
  vertex: new VertexAdapter(),
};

export function adapterFor(protocol: ProviderProtocol): ProviderAdapter {
  const a = ADAPTERS[protocol];
  if (!a) throw new Error(`No adapter registered for protocol ${protocol}`);
  return a;
}

/** Get the streaming translator for a provider. */
export function streamChunkTranslator(provider: ProviderConfig): (raw: string) => unknown[] {
  switch (provider.protocol) {
    case "openai": {
      return (raw) => {
        const obj = translateOpenAIChunk(raw);
        return obj ? [obj] : [];
      };
    }
    case "anthropic":
      return translateAnthropicStreamChunk;
    case "gemini":
      return translateGeminiStreamChunk;
    case "cohere":
      return translateCohereStreamChunk;
    case "bedrock":
      return translateBedrockStreamChunk;
    case "vertex":
      return translateVertexStreamChunk;
  }
}
