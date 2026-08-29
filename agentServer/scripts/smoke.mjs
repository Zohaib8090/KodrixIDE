#!/usr/bin/env node
/**
 * Smoke test for the Kodrix agent server.
 * Assumes the server is already running on http://127.0.0.1:3080.
 *
 *   node scripts/smoke.mjs
 *
 * Exits 0 on success, 1 on any failure.
 */

const BASE = process.env.SMOKE_BASE ?? "http://127.0.0.1:3080";

async function step(name, fn) {
  process.stdout.write(`• ${name} ... `);
  try {
    await fn();
    process.stdout.write("OK\n");
  } catch (err) {
    process.stdout.write(`FAIL: ${err.message}\n`);
    process.exit(1);
  }
}

async function main() {
  await step("GET /healthz", async () => {
    const r = await fetch(`${BASE}/healthz`);
    if (!r.ok) throw new Error(`status ${r.status}`);
    const j = await r.json();
    if (!j.ok) throw new Error("ok != true");
  });

  await step("GET /providers", async () => {
    const r = await fetch(`${BASE}/providers`);
    if (!r.ok) throw new Error(`status ${r.status}`);
    const j = await r.json();
    if (!Array.isArray(j.providers)) throw new Error("providers not array");
    if (j.providers.length < 1) throw new Error("expected seeded providers");
  });

  await step("GET /v1/models", async () => {
    const r = await fetch(`${BASE}/v1/models`);
    if (!r.ok) throw new Error(`status ${r.status}`);
    const j = await r.json();
    if (j.object !== "list") throw new Error("object != list");
  });

  await step("POST /providers (create custom OpenAI-compat)", async () => {
    const r = await fetch(`${BASE}/providers`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        id: "smoke-test",
        label: "Smoke Test",
        protocol: "openai",
        baseUrl: "https://example.invalid",
        apiKeyRef: "literal:sk-smoke",
        models: ["smoke-model"],
        supportsReasoning: false,
        streaming: true,
        enabled: true,
      }),
    });
    if (!r.ok) throw new Error(`status ${r.status}: ${await r.text()}`);
  });

  await step("DELETE /providers/smoke-test", async () => {
    const r = await fetch(`${BASE}/providers/smoke-test`, { method: "DELETE" });
    if (!r.ok) throw new Error(`status ${r.status}`);
  });

  await step("POST /v1/chat/completions (no provider for model — should 404)", async () => {
    const r = await fetch(`${BASE}/v1/chat/completions`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        model: "no-such-model-xyz",
        messages: [{ role: "user", content: "hi" }],
      }),
    });
    if (r.status !== 404) throw new Error(`expected 404, got ${r.status}`);
  });

  console.log("\n✓ All smoke tests passed.");
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
