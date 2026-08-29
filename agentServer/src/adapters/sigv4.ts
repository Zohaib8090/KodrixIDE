/**
 * AWS Signature Version 4 signer (SigV4).
 *
 * Used by the Bedrock adapter to authenticate every request. Per AWS
 * docs: https://docs.aws.amazon.com/IAM/latest/UserGuide/reference_sigv-create-signed-request.html
 *
 * We sign the canonical request, build the string-to-sign, derive a
 * signing key, compute the HMAC-SHA256 signature, and place it in
 * the `Authorization` header along with the credential scope and
 * signed headers list.
 *
 * Why hand-rolled: AWS SDKs are heavy and bring transitive deps that
 * would dwarf the whole agent server. The signature algorithm is
 * ~80 lines of focused code, all using the Node `crypto` module.
 */
import { createHash, createHmac } from "node:crypto";

export interface SigV4Credentials {
  accessKeyId: string;
  secretAccessKey: string;
  sessionToken?: string;
  region: string;
  service: string;
}

export interface SignedRequest {
  headers: Record<string, string>;
  /** Empty string for GET; for POST, the raw request body. */
  body: string;
}

/**
 * Sign an outgoing request and return the headers to add (or overwrite).
 * The caller should spread these into their existing headers.
 */
export function signRequest(
  method: "GET" | "POST" | "PUT" | "DELETE",
  url: URL,
  body: string,
  creds: SigV4Credentials,
  extraHeaders: Record<string, string> = {}
): Record<string, string> {
  const now = new Date();
  const amzDate = formatAmzDate(now);
  const dateStamp = amzDate.slice(0, 8);

  // 1. Canonical request
  const canonicalHeaders = buildCanonicalHeaders(url.host, amzDate, creds, extraHeaders);
  const signedHeaders = Object.keys(canonicalHeaders)
    .map((k) => k.toLowerCase())
    .sort()
    .join(";");

  const payloadHash = sha256Hex(body);
  const canonicalRequest = [
    method,
    url.pathname || "/",
    url.search.slice(1), // query string without leading "?"
    Object.keys(canonicalHeaders)
      .sort()
      .map((k) => `${k.toLowerCase()}:${canonicalHeaders[k]}`)
      .join("\n") + "\n",
    signedHeaders,
    payloadHash,
  ].join("\n");

  // 2. String to sign
  const credentialScope = `${dateStamp}/${creds.region}/${creds.service}/aws4_request`;
  const stringToSign = ["AWS4-HMAC-SHA256", amzDate, credentialScope, sha256Hex(canonicalRequest)].join(
    "\n"
  );

  // 3. Derive signing key
  const kDate = hmac("AWS4" + creds.secretAccessKey, dateStamp);
  const kRegion = hmac(kDate, creds.region);
  const kService = hmac(kRegion, creds.service);
  const kSigning = hmac(kService, "aws4_request");

  // 4. Compute signature
  const signature = hmacHex(kSigning, stringToSign);

  const authorization =
    `AWS4-HMAC-SHA256 Credential=${creds.accessKeyId}/${credentialScope}, ` +
    `SignedHeaders=${signedHeaders}, Signature=${signature}`;

  return {
    ...canonicalHeaders,
    Authorization: authorization,
    ...(creds.sessionToken ? { "X-Amz-Security-Token": creds.sessionToken } : {}),
  };
}

function buildCanonicalHeaders(
  host: string,
  amzDate: string,
  creds: SigV4Credentials,
  extra: Record<string, string>
): Record<string, string> {
  const headers: Record<string, string> = {
    host,
    "x-amz-date": amzDate,
    ...extra,
  };
  // Lowercase keys, trim, collapse internal whitespace.
  for (const [k, v] of Object.entries(headers)) {
    delete headers[k];
    headers[k.toLowerCase()] = v.trim().replace(/\s+/g, " ");
  }
  return headers;
}

function formatAmzDate(d: Date): string {
  const pad = (n: number) => n.toString().padStart(2, "0");
  return (
    d.getUTCFullYear().toString() +
    pad(d.getUTCMonth() + 1) +
    pad(d.getUTCDate()) +
    "T" +
    pad(d.getUTCHours()) +
    pad(d.getUTCMinutes()) +
    pad(d.getUTCSeconds()) +
    "Z"
  );
}

function sha256Hex(s: string): string {
  return createHash("sha256").update(s, "utf8").digest("hex");
}

function hmac(key: string | Buffer, data: string): Buffer {
  return createHmac("sha256", key).update(data, "utf8").digest();
}

function hmacHex(key: string | Buffer, data: string): string {
  return createHmac("sha256", key).update(data, "utf8").digest("hex");
}

/**
 * Parse the apiKeyRef for a Bedrock provider into a credentials object.
 * Format: "keyId:secretAccessKey:region" or "keyId:secretAccessKey:region:sessionToken"
 *   - colons are reserved; if the secret contains a colon, this won't work
 *     — that's a known limitation, fix by using file:/path scheme
 */
export function parseBedrockApiKey(ref: string): SigV4Credentials {
  const parts = ref.split(":");
  if (parts.length < 3) {
    throw new Error(
      `Bedrock apiKeyRef must be "keyId:secretAccessKey:region" (got ${ref.length} chars; check for colons in secret)`
    );
  }
  const [accessKeyId, secretAccessKey, region, ...rest] = parts;
  return {
    accessKeyId: accessKeyId!,
    secretAccessKey: secretAccessKey!,
    region: region!,
    service: "bedrock",
    ...(rest.length > 0 && rest[0] ? { sessionToken: rest.join(":") } : {}),
  };
}
