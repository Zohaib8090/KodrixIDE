/**
 * Server entry point.
 *
 *   node dist/server.js [--port 3080] [--host 127.0.0.1] [--idle-timeout-ms 720000]
 *
 * The server is meant to be spawned on demand by the Kodrix IDE.
 * Self-terminates after `--idle-timeout-ms` of no request activity.
 */
import Fastify, { type FastifyInstance } from "fastify";
import cors from "@fastify/cors";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { ProviderRegistry } from "./core/providers.js";
import { IdleTimer } from "./core/idle-timer.js";
import { registerProviderRoutes } from "./routes/providers.js";
import { registerChatRoutes } from "./routes/chat.js";

interface CliArgs {
  port: number;
  host: string;
  idleTimeoutMs: number;
}

function parseArgs(argv: string[]): CliArgs {
  const args: CliArgs = { port: 3080, host: "127.0.0.1", idleTimeoutMs: 12 * 60 * 1000 };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === "--port") args.port = Number(argv[++i]);
    else if (a === "--host") args.host = String(argv[++i]);
    else if (a === "--idle-timeout-ms") args.idleTimeoutMs = Number(argv[++i]);
  }
  if (!Number.isFinite(args.port) || args.port <= 0 || args.port > 65535) {
    throw new Error(`Invalid --port: ${args.port}`);
  }
  if (!Number.isFinite(args.idleTimeoutMs) || args.idleTimeoutMs < 1000) {
    throw new Error(`Invalid --idle-timeout-ms: ${args.idleTimeoutMs}`);
  }
  return args;
}

export async function buildServer(args: CliArgs): Promise<{ app: FastifyInstance; idle: IdleTimer }> {
  const registry = new ProviderRegistry();
  await registry.init();

  const app = Fastify({
    logger: {
      level: process.env.LOG_LEVEL ?? "info",
      // Minimal pretty output in dev; plain JSON in production. pino-pretty
      // is an OPTIONAL dev dep — if it's not installed we fall back to plain
      // JSON logs (still structured, just less colorful).
      transport: undefined,
    },
    // SSE-friendly: do not time out long-running streams.
    connectionTimeout: 0,
    keepAliveTimeout: 5 * 60 * 1000,
    requestTimeout: 0,
    bodyLimit: 8 * 1024 * 1024, // 8 MB — enough for big tool payloads, not absurd.
  });

  await app.register(cors, { origin: true });

  // Health check.
  app.get("/healthz", async () => ({
    ok: true,
    pid: process.pid,
    uptimeSec: Math.floor(process.uptime()),
    providers: registry.list().length,
  }));

  registerProviderRoutes(app, registry);
  registerChatRoutes(app, registry);

  // Bump the idle timer on every completed request.
  const idle = new IdleTimer({
    timeoutMs: args.idleTimeoutMs,
    onTimeout: () => {
      // eslint-disable-next-line no-console
      console.log(`[idle] no activity for ${args.idleTimeoutMs}ms; shutting down`);
      app.close().finally(() => process.exit(0));
    },
  });
  await idle.start();
  app.addHook("onResponse", async () => {
    idle.bump();
  });
  app.addHook("onError", async () => {
    idle.bump();
  });

  return { app, idle };
}

async function main(): Promise<void> {
  const args = parseArgs(process.argv.slice(2));
  const { app } = await buildServer(args);
  // eslint-disable-next-line no-console
  console.log(
    `[kodrix-agent] listening on http://${args.host}:${args.port} (idle timeout ${args.idleTimeoutMs}ms)`
  );
  await app.listen({ host: args.host, port: args.port });

  // Graceful shutdown on signals.
  for (const sig of ["SIGINT", "SIGTERM"] as const) {
    process.once(sig, () => {
      // eslint-disable-next-line no-console
      console.log(`[kodrix-agent] received ${sig}; shutting down`);
      app.close().finally(() => process.exit(0));
    });
  }
}

// Only run when invoked directly (not when imported by tests).
// We check argv[1] match at the end because Windows-style paths don't
// always match `import.meta.url` for `file://` comparisons.
const invokedDirectly = (() => {
  if (!process.argv[1]) return false;
  try {
    return fileURLToPath(import.meta.url) === path.resolve(process.argv[1]);
  } catch {
    return true; // best-effort: just run
  }
})();
if (invokedDirectly) {
  main().catch((err) => {
    // eslint-disable-next-line no-console
    console.error("[kodrix-agent] fatal:", err);
    process.exit(1);
  });
}
