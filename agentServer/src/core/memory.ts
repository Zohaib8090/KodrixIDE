/**
 * Two-tier memory for chat sessions.
 *
 * Tier 1 — rolling window: keep the last N user/assistant turns verbatim.
 *          The model sees the full recent conversation.
 * Tier 2 — summarization: when the rolling window exceeds N turns, ask the
 *          model to summarize the oldest ones into a single system note,
 *          and prepend that to the system prompt.
 *
 * This is the fix for two failure modes:
 *   - hard context-limit crashes (naive truncation can also hit, but
 *     summarization explicitly fits the budget)
 *   - silent quality collapse from naive mid-instruction truncation
 *
 * The summarizer is pluggable so it can use any provider on any model.
 */
import type { CanonicalChatMessage } from "../types.js";
import { adapterFor } from "../adapters/registry.js";
import { resolveApiKey, type ProviderRegistry } from "./providers.js";

export interface MemoryOptions {
  /** Keep this many recent turns verbatim. Defaults to 20. */
  windowSize: number;
  /** Summarize when history grows past this many messages. Defaults to windowSize * 2. */
  summarizeThreshold: number;
  /** Provider id to use for the summarization call. */
  summarizerProviderId: string;
  /** Model to use for the summarization call. Must support text generation. */
  summarizerModel: string;
}

export const DEFAULT_MEMORY_OPTIONS: MemoryOptions = {
  windowSize: 20,
  summarizeThreshold: 40,
  summarizerProviderId: "deepseek",
  summarizerModel: "deepseek-chat",
};

export interface MemoryResult {
  /** Messages to send to the model. The summary (if any) is prepended as a system message. */
  messages: CanonicalChatMessage[];
  /** Whether a summary was injected. */
  summarized: boolean;
}

export async function prepareMessages(
  history: CanonicalChatMessage[],
  options: MemoryOptions,
  registry: ProviderRegistry,
  systemPrompt: string | null
): Promise<MemoryResult> {
  // If under threshold, no work to do.
  if (history.length <= options.summarizeThreshold) {
    return {
      messages: prependSystem(history, systemPrompt),
      summarized: false,
    };
  }

  // Split: oldest to summarize, recent to keep verbatim.
  const cut = history.length - options.windowSize;
  const toSummarize = history.slice(0, cut);
  const recent = history.slice(cut);

  const summary = await summarize(toSummarize, options, registry);
  const augmentedSystem = systemPrompt
    ? `${systemPrompt}\n\n[Conversation summary]\n${summary}`
    : `[Conversation summary]\n${summary}`;

  return {
    messages: prependSystem(recent, augmentedSystem),
    summarized: true,
  };
}

function prependSystem(messages: CanonicalChatMessage[], system: string | null): CanonicalChatMessage[] {
  if (!system) return messages;
  return [{ role: "system", content: system }, ...messages];
}

async function summarize(
  messages: CanonicalChatMessage[],
  options: MemoryOptions,
  registry: ProviderRegistry
): Promise<string> {
  const provider = registry.get(options.summarizerProviderId);
  if (!provider) throw new Error(`Summarizer provider ${options.summarizerProviderId} not found`);
  if (!provider.enabled) {
    throw new Error(`Summarizer provider ${options.summarizerProviderId} is disabled`);
  }
  const apiKey = resolveApiKey(provider.apiKeyRef);
  const adapter = adapterFor(provider.protocol);

  // Build a compact transcript for the summarizer.
  const transcript = messages
    .map((m) => {
      const text = typeof m.content === "string" ? m.content : "(non-text)";
      return `${m.role.toUpperCase()}: ${text}`;
    })
    .join("\n");

  const result = await adapter.execute(
    provider,
    {
      model: options.summarizerModel,
      messages: [
        {
          role: "system",
          content:
            "You are a conversation summarizer. Produce a concise summary that preserves: (1) user goals, (2) decisions made, (3) open questions, (4) tool names used and what they did. Output plain prose, no headings.",
        },
        { role: "user", content: transcript },
      ],
      stream: false,
      max_tokens: 800,
    },
    { apiKey, signal: AbortSignal.timeout(30_000) }
  );

  if (result.kind !== "json") {
    throw new Error("Summarizer returned a stream — expected non-streaming response");
  }
  const body = result.body as {
    choices?: Array<{ message?: { content?: string | null } }>;
  };
  const text = body.choices?.[0]?.message?.content;
  if (!text) throw new Error("Summarizer produced empty content");
  return text;
}
