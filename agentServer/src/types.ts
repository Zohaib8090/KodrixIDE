/**
 * Canonical types for the Kodrix agent server.
 *
 * Design rule: the SERVER-internal canonical format is OpenAI Chat Completions.
 * Provider adapters translate FROM their native shape INTO this canonical
 * format on the way IN, and FROM this canonical format INTO their native
 * shape on the way OUT. That keeps format-parsing bugs isolated per adapter
 * (a Gemini bug cannot corrupt OpenAI-format traffic).
 */

// ---------- Provider identity ----------

export type ProviderProtocol = "openai" | "anthropic" | "gemini";

export interface ProviderConfig {
  /** Stable id used in API paths. Lowercase, no spaces. */
  id: string;
  /** Human label shown in the IDE. */
  label: string;
  /** Which wire protocol this provider speaks natively. */
  protocol: ProviderProtocol;
  /**
   * Base URL of the provider API. Trailing slash is normalized away.
   * Examples:
   *   OpenAI:    "https://api.openai.com"
   *   DeepSeek:  "https://api.deepseek.com"
   *   Anthropic: "https://api.anthropic.com"
   *   Gemini:    "https://generativelanguage.googleapis.com"
   *   Ollama:    "http://localhost:11434"
   */
  baseUrl: string;
  /**
   * API key for the provider. Stored as a reference; resolved at request
   * time from the credential store (env, providers.json, or a future
   * OS keychain integration).
   */
  apiKeyRef: string;
  /** Models the user wants exposed for this provider. */
  models: string[];
  /** Whether to send a thinking/reasoning trace back to the client. */
  supportsReasoning: boolean;
  /** When true, the server may keep idle timer alive while the provider is slow. */
  streaming: boolean;
  /** Disabled providers are kept in the file but ignored at request time. */
  enabled: boolean;
}

// ---------- Canonical (OpenAI-shape) chat request types ----------

export interface CanonicalChatMessage {
  role: "system" | "user" | "assistant" | "tool";
  /** For assistant messages, may include tool_calls (set by the model). */
  content: string | null;
  /** For assistant messages that called tools. */
  tool_calls?: CanonicalToolCall[];
  /** For tool messages: which call this is the result of. */
  tool_call_id?: string;
  /** Optional name field (Anthropic requires it; we keep it optional). */
  name?: string;
}

export interface CanonicalToolCall {
  id: string;
  type: "function";
  function: {
    name: string;
    /** JSON-encoded string of arguments, per OpenAI's shape. */
    arguments: string;
  };
}

export interface CanonicalToolDefinition {
  type: "function";
  function: {
    name: string;
    description: string;
    /** JSON Schema for the function arguments. */
    parameters: Record<string, unknown>;
  };
}

export interface CanonicalChatRequest {
  /** Model id, e.g. "deepseek-chat" or "claude-sonnet-4-5". */
  model: string;
  messages: CanonicalChatMessage[];
  tools?: CanonicalToolDefinition[];
  /** Optional tool-choice strategy. */
  tool_choice?: "auto" | "none" | "required" | { type: "function"; function: { name: string } };
  temperature?: number;
  top_p?: number;
  max_tokens?: number;
  stop?: string | string[];
  stream?: boolean;
  /**
   * The provider id to route to. If omitted, the server resolves the
   * provider from the model name (e.g. "deepseek-chat" → "deepseek").
   */
  provider?: string;
}

// ---------- Canonical response types ----------

export interface CanonicalChatChoice {
  index: number;
  message: CanonicalChatMessage;
  finish_reason: "stop" | "tool_calls" | "length" | "content_filter" | null;
}

export interface CanonicalChatResponse {
  id: string;
  object: "chat.completion";
  created: number;
  model: string;
  choices: CanonicalChatChoice[];
  usage?: {
    prompt_tokens: number;
    completion_tokens: number;
    total_tokens: number;
  };
}

export interface CanonicalChatChunk {
  id: string;
  object: "chat.completion.chunk";
  created: number;
  model: string;
  choices: Array<{
    index: number;
    delta: Partial<CanonicalChatMessage>;
    finish_reason: "stop" | "tool_calls" | "length" | "content_filter" | null;
  }>;
}

// ---------- Provider adapter contract ----------

export interface ProviderAdapter {
  /** Which protocol this adapter handles. */
  readonly protocol: ProviderProtocol;

  /**
   * Translate a canonical request into this provider's native wire format
   * and execute it. Returns either a full response (non-streaming) or an
   * AsyncIterable of raw provider-format SSE chunks (streaming).
   *
   * The adapter does NOT translate back — that is the caller's job, using
   * `translateResponseChunk`. This split is what isolates format bugs:
   * a bad Gemini response parser cannot break an OpenAI-format request.
   */
  execute(
    provider: ProviderConfig,
    request: CanonicalChatRequest,
    options: { apiKey: string; signal: AbortSignal }
  ): Promise<AdapterResult>;
}

export type AdapterResult =
  | { kind: "json"; body: unknown }
  | {
      kind: "stream";
      /** Each item is a raw provider-format SSE data payload, e.g. "data: {...}\n\n". */
      stream: AsyncIterable<string>;
    };

/**
 * Translate a single raw provider-format SSE payload into one or more
 * canonical OpenAI-format chunks. Implementations MUST be pure and
 * MAY be called on many chunks; the server concatenates them to the client.
 */
export type ChunkTranslator = (rawPayload: string) => CanonicalChatChunk[] | null;

/** Resolve and return the streaming translator for a given protocol. */
export type ChunkTranslatorResolver = (provider: ProviderConfig) => ChunkTranslator;

// ---------- Provider test ----------

export interface ProviderTestResult {
  ok: boolean;
  latencyMs?: number;
  modelsAvailable?: string[];
  error?: string;
}
