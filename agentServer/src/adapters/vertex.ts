/**
 * Google Vertex AI adapter.
 *
 * Same model families as the plain Gemini API, BUT:
 *   - auth is a GCP service account, not an API key
 *   - endpoint is regional (e.g. us-central1-aiplatform.googleapis.com)
 *   - URL path: /v1/projects/{project}/locations/{region}/publishers/google/models/{model}:generateContent
 *   - request/response body is otherwise the SAME as Gemini
 *
 * We do the OAuth2 client_credentials flow manually:
 *   1. Sign a self-JWT with the service account's private key
 *   2. POST it to https://oauth2.googleapis.com/token with grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer
 *   3. Cache the access_token until ~5 min before expiry
 *
 * apiKeyRef format for Vertex:
 *   "literal:{json-service-account}" — the entire service-account JSON
 *   (or use file:/path.json to point at a JSON file)
 */
import { createSign } from "node:crypto";
import type {
  AdapterResult,
  CanonicalChatRequest,
  ProviderAdapter,
  ProviderConfig,
} from "../types.js";
import { promises as fs } from "node:fs";
import { translateRequest as geminiTranslateRequest, translateResponse as geminiTranslateResponse, translateGeminiStreamChunk } from "./gemini.js";

interface ServiceAccountKey {
  type: string;
  project_id: string;
  private_key_id: string;
  private_key: string;
  client_email: string;
  token_uri?: string;
}

interface TokenCacheEntry {
  token: string;
  expiresAt: number;
}

let tokenCache: TokenCacheEntry | null = null;
let tokenCacheKey: string | null = null; // fingerprint of the service account

export class VertexAdapter implements ProviderAdapter {
  readonly protocol = "vertex" as const;

  async execute(
    provider: ProviderConfig,
    request: CanonicalChatRequest,
    options: { apiKey: string; signal: AbortSignal }
  ): Promise<AdapterResult> {
    const key = await loadServiceAccount(options.apiKey);
    const token = await getAccessToken(key);

    // baseUrl format: "https://{region}-aiplatform.googleapis.com"
    const baseUrl = stripTrailingSlash(provider.baseUrl);
    const action = request.stream ? "streamGenerateContent" : "generateContent";
    const path = `/v1/projects/${encodeURIComponent(key.project_id)}/locations/global/publishers/google/models/${encodeURIComponent(request.model)}:${action}`;
    const url = `${baseUrl}${path}`;

    // Body is identical to plain Gemini. We reuse the Gemini translator.
    const body = JSON.stringify(geminiTranslateRequest(request));

    const res = await fetch(url, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${token}`,
      },
      body,
      signal: options.signal,
    });

    if (!res.ok) {
      const text = await res.text();
      throw new Error(`Vertex ${provider.id} returned ${res.status}: ${text}`);
    }

    if (request.stream) {
      if (!res.body) throw new Error(`Vertex ${provider.id}: no body on streaming response`);
      // Reuse the Gemini SSE framing — same wire format on the wire.
      // Wrap the raw stream as a string-passing stream for the unified pipeline.
      return { kind: "stream", stream: streamRawSse(res.body) };
    }
    const json = (await res.json()) as unknown;
    return { kind: "json", body: geminiTranslateResponse(json) };
  }
}

// ---------- Service account loading ----------

async function loadServiceAccount(ref: string): Promise<ServiceAccountKey> {
  let raw: string;
  if (ref.startsWith("file:")) {
    raw = (await fs.readFile(ref.slice(5), "utf8")).trim();
  } else if (ref.startsWith("literal:")) {
    raw = ref.slice(8);
  } else if (ref.startsWith("{")) {
    raw = ref;
  } else {
    throw new Error(`Vertex apiKeyRef must start with literal: or file: (got: ${ref.slice(0, 20)}...)`);
  }
  let parsed: ServiceAccountKey;
  try {
    parsed = JSON.parse(raw) as ServiceAccountKey;
  } catch (err) {
    throw new Error(`Vertex service account JSON is invalid: ${(err as Error).message}`);
  }
  for (const field of ["type", "project_id", "private_key", "client_email"] as const) {
    if (!parsed[field]) {
      throw new Error(`Vertex service account JSON missing field: ${field}`);
    }
  }
  return parsed;
}

// ---------- OAuth2 JWT-bearer token ----------

async function getAccessToken(key: ServiceAccountKey): Promise<string> {
  const cacheKey = key.client_email + ":" + key.private_key_id;
  const now = Date.now();
  if (tokenCache && tokenCacheKey === cacheKey && tokenCache.expiresAt > now + 60_000) {
    return tokenCache.token;
  }

  const tokenUri = key.token_uri ?? "https://oauth2.googleapis.com/token";
  const iat = Math.floor(now / 1000);
  const exp = iat + 3600; // 1 hour

  const claim = {
    iss: key.client_email,
    scope: "https://www.googleapis.com/auth/cloud-platform",
    aud: tokenUri,
    iat,
    exp,
  };
  const header = { alg: "RS256", typ: "JWT" };
  const enc = (obj: unknown) => Buffer.from(JSON.stringify(obj)).toString("base64url");
  const signatureInput = `${enc(header)}.${enc(claim)}`;
  const signer = createSign("RSA-SHA256");
  signer.update(signatureInput);
  const signature = signer.sign(key.private_key, "base64url");
  const jwt = `${signatureInput}.${signature}`;

  const body = new URLSearchParams({
    grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
    assertion: jwt,
  });
  const res = await fetch(tokenUri, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: body.toString(),
  });
  if (!res.ok) {
    const text = await res.text();
    throw new Error(`Vertex token exchange failed (${res.status}): ${text}`);
  }
  const out = (await res.json()) as { access_token: string; expires_in: number };
  tokenCache = {
    token: out.access_token,
    expiresAt: now + out.expires_in * 1000,
  };
  tokenCacheKey = cacheKey;
  return out.access_token;
}

// ---------- SSE pass-through (Vertex uses same framing as Gemini) ----------

async function* streamRawSse(body: ReadableStream<Uint8Array>): AsyncGenerator<string, void, undefined> {
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

// ---------- helpers ----------

function stripTrailingSlash(s: string): string {
  return s.endsWith("/") ? s.slice(0, -1) : s;
}

// Re-export the Gemini stream translator; Vertex chunks are wire-identical.
export { translateGeminiStreamChunk, translateGeminiStreamChunk as translateVertexStreamChunk } from "./gemini.js";
export function translateRequest(
  req: CanonicalChatRequest
): import("./gemini.js").GeminiRequest {
  return geminiTranslateRequest(req);
}
export function translateResponse(raw: unknown): unknown {
  return geminiTranslateResponse(raw);
}
