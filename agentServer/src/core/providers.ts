import { promises as fs } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import type { ProviderConfig } from "../types.js";

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

/**
 * On-disk location: <repo>/agentServer/data/providers.json
 * Resolved relative to this file so it works both in source and compiled form.
 */
export const DATA_DIR = path.resolve(__dirname, "..", "..", "data");
export const PROVIDERS_FILE = path.join(DATA_DIR, "providers.json");

/**
 * The seeded provider list the IDE will pre-fill on first run.
 * NOTE: apiKeyRef is an opaque key — the credential store resolves it
 * (env > providers.json `secrets` block > future keychain). No secrets
 * are checked into git.
 */
const SEED: ProviderConfig[] = [
  {
    id: "deepseek",
    label: "DeepSeek",
    protocol: "openai",
    baseUrl: "https://api.deepseek.com",
    apiKeyRef: "env:DEEPSEEK_API_KEY",
    models: ["deepseek-chat", "deepseek-reasoner"],
    supportsReasoning: true,
    streaming: true,
    enabled: true,
  },
  {
    id: "openai",
    label: "OpenAI",
    protocol: "openai",
    baseUrl: "https://api.openai.com",
    apiKeyRef: "env:OPENAI_API_KEY",
    models: ["gpt-4o-mini", "gpt-4o", "o4-mini"],
    supportsReasoning: true,
    streaming: true,
    enabled: true,
  },
  {
    id: "anthropic",
    label: "Anthropic",
    protocol: "anthropic",
    baseUrl: "https://api.anthropic.com",
    apiKeyRef: "env:ANTHROPIC_API_KEY",
    models: ["claude-sonnet-4-5", "claude-haiku-4-5"],
    supportsReasoning: true,
    streaming: true,
    enabled: true,
  },
  {
    id: "gemini",
    label: "Google Gemini",
    protocol: "gemini",
    baseUrl: "https://generativelanguage.googleapis.com",
    apiKeyRef: "env:GEMINI_API_KEY",
    models: ["gemini-2.5-flash", "gemini-2.5-pro"],
    supportsReasoning: true,
    streaming: true,
    enabled: true,
  },
  {
    id: "ollama-local",
    label: "Ollama (local)",
    protocol: "openai",
    baseUrl: "http://localhost:11434",
    apiKeyRef: "literal:ollama",
    models: [],
    supportsReasoning: false,
    streaming: true,
    enabled: false, // off by default — user opts in once they've installed Ollama
  },
];

interface ProvidersFile {
  version: 1;
  providers: ProviderConfig[];
}

/**
 * In-memory provider index with atomic disk persistence.
 *
 * Why JSON instead of SQLite: at this scale, schema migrations and lock
 * contention are pure failure modes. A corrupted file is recoverable
 * by hand-editing; SQLite WAL corruption on a hard crash is not.
 */
export class ProviderRegistry {
  private providers: Map<string, ProviderConfig> = new Map();
  private initialized = false;

  async init(): Promise<void> {
    if (this.initialized) return;
    await fs.mkdir(DATA_DIR, { recursive: true });

    let data: ProvidersFile;
    try {
      const raw = await fs.readFile(PROVIDERS_FILE, "utf8");
      data = JSON.parse(raw) as ProvidersFile;
    } catch (err: unknown) {
      const code = (err as NodeJS.ErrnoException).code;
      if (code === "ENOENT") {
        // First run: seed and persist.
        data = { version: 1, providers: SEED };
        await this.persist(data);
      } else {
        // Corrupt file: don't silently overwrite. Surface the error so the
        // operator can decide (rename the file, fix it, etc.).
        throw new Error(
          `Failed to read ${PROVIDERS_FILE}: ${(err as Error).message}. ` +
            `Fix the file or delete it to re-seed on next start.`
        );
      }
    }

    for (const p of data.providers) {
      validateProvider(p);
      this.providers.set(p.id, p);
    }
    this.initialized = true;
  }

  list(): ProviderConfig[] {
    return [...this.providers.values()];
  }

  listEnabled(): ProviderConfig[] {
    return this.list().filter((p) => p.enabled);
  }

  get(id: string): ProviderConfig | undefined {
    return this.providers.get(id);
  }

  /**
   * Find a provider for a model id. Strategy: exact id match first, then
   * any provider whose `models` list contains the id.
   */
  resolveForModel(model: string): ProviderConfig | undefined {
    for (const p of this.providers.values()) {
      if (!p.enabled) continue;
      if (p.models.includes(model)) return p;
    }
    return undefined;
  }

  async upsert(p: ProviderConfig): Promise<void> {
    validateProvider(p);
    this.providers.set(p.id, p);
    await this.flush();
  }

  async remove(id: string): Promise<boolean> {
    const existed = this.providers.delete(id);
    if (existed) await this.flush();
    return existed;
  }

  private async flush(): Promise<void> {
    const data: ProvidersFile = {
      version: 1,
      providers: [...this.providers.values()],
    };
    await this.persist(data);
  }

  /**
   * Atomic write: write to a temp file in the same directory, then rename.
   * Avoids half-written files if the process is killed mid-write.
   */
  private async persist(data: ProvidersFile): Promise<void> {
    await fs.mkdir(DATA_DIR, { recursive: true });
    const tmp = path.join(DATA_DIR, `.providers.${process.pid}.${Date.now()}.tmp`);
    await fs.writeFile(tmp, JSON.stringify(data, null, 2), "utf8");
    await fs.rename(tmp, PROVIDERS_FILE);
  }
}

function validateProvider(p: ProviderConfig): void {
  if (!p.id || !/^[a-z0-9][a-z0-9-_]{0,63}$/.test(p.id)) {
    throw new Error(`Invalid provider id: ${JSON.stringify(p.id)}`);
  }
  if (!p.label?.trim()) throw new Error(`Provider ${p.id}: label required`);
  if (!["openai", "anthropic", "gemini"].includes(p.protocol)) {
    throw new Error(`Provider ${p.id}: unknown protocol ${p.protocol}`);
  }
  if (!p.baseUrl?.startsWith("http")) {
    throw new Error(`Provider ${p.id}: baseUrl must be http(s)`);
  }
  if (!p.apiKeyRef?.trim()) {
    throw new Error(`Provider ${p.id}: apiKeyRef required`);
  }
  if (!Array.isArray(p.models)) {
    throw new Error(`Provider ${p.id}: models must be an array`);
  }
}

/**
 * Resolve an apiKeyRef to a concrete key.
 * Supported schemes:
 *   env:VAR_NAME   -> process.env.VAR_NAME
 *   literal:value  -> "value" (use for non-secret cases like Ollama)
 *   file:/path     -> contents of file
 *   secret:NAME    -> providers.json `secrets.NAME` (future; throws for now)
 */
export function resolveApiKey(ref: string): string {
  if (ref.startsWith("env:")) {
    const name = ref.slice(4);
    const v = process.env[name];
    if (!v) throw new Error(`Missing env var ${name} for apiKeyRef ${ref}`);
    return v;
  }
  if (ref.startsWith("literal:")) return ref.slice(8);
  if (ref.startsWith("file:")) {
    // Synchronous fs.readFileSync is fine here — keys are tiny.
    return require("node:fs").readFileSync(ref.slice(5), "utf8").trim();
  }
  if (ref.startsWith("secret:")) {
    throw new Error(
      `secret: scheme not yet implemented (ref=${ref}). ` +
        `Use env:VAR for now; a future version will add encrypted secret storage.`
    );
  }
  throw new Error(`Unknown apiKeyRef scheme: ${ref}`);
}
