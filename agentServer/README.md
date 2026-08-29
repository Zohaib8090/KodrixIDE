# Kodrix Agent Server

A small, focused HTTP server that fronts multiple LLM providers (DeepSeek,
OpenAI, Anthropic, Gemini, Ollama, custom OpenAI-compatible endpoints)
behind a single OpenAI-compatible chat-completions surface.

This is the **server half** of the Kodrix IDE agent system. The IDE
spawns it on demand when the user opens the Agent panel and kills it
when the panel closes. The server also self-terminates after a long
period of inactivity (default 12 minutes) so a crashed IDE never
leaves a zombie child process behind.

## Quick start

```bash
cd agentServer
npm install
npm run build
node dist/server.js --port 3080
```

Then in another terminal:

```bash
node scripts/smoke.mjs
```

## Configuration

Providers are stored in `data/providers.json` (created on first run with
sensible defaults). Each provider entry looks like:

```json
{
  "id": "deepseek",
  "label": "DeepSeek",
  "protocol": "openai",
  "baseUrl": "https://api.deepseek.com",
  "apiKeyRef": "env:DEEPSEEK_API_KEY",
  "models": ["deepseek-chat", "deepseek-reasoner"],
  "supportsReasoning": true,
  "streaming": true,
  "enabled": true
}
```

`apiKeyRef` is an opaque reference, NOT the key itself. Supported schemes:

| Scheme         | Resolves to                                  |
| -------------- | -------------------------------------------- |
| `env:NAME`     | `process.env.NAME`                           |
| `literal:X`    | The string `X` (use for keyless local APIs)  |
| `file:/path`   | Contents of the file                         |
| `secret:NAME`  | Reserved for future encrypted store          |

Set the env vars your providers need before starting the server:

```bash
export DEEPSEEK_API_KEY=sk-...
export OPENAI_API_KEY=sk-...
export ANTHROPIC_API_KEY=sk-ant-...
export GEMINI_API_KEY=AIza...
node dist/server.js
```

## API

| Method | Path                              | Purpose                                |
| ------ | --------------------------------- | -------------------------------------- |
| GET    | `/healthz`                        | Liveness + provider count              |
| GET    | `/providers`                      | List all configured providers          |
| GET    | `/providers/:id`                  | Get one provider                       |
| POST   | `/providers`                      | Create or overwrite a provider         |
| PATCH  | `/providers/:id`                  | Partial update                         |
| DELETE | `/providers/:id`                  | Remove a provider                      |
| POST   | `/providers/:id/test`             | Probe connectivity + report latency    |
| GET    | `/v1/models`                      | List all enabled models (OpenAI shape) |
| POST   | `/v1/chat/completions`            | **The** unified chat-completions API   |
| POST   | `/chat`                           | Same as above, friendlier path         |

`/v1/chat/completions` accepts the standard OpenAI body. To force a
specific provider, add `"provider": "anthropic"` to the body.

## Architecture

```
+---------------------+      +-------------------+      +-----------------+
| Kodrix IDE (Kotlin) | ---> |  agent server     | ---> | Provider API    |
|  spawns on demand   | HTTP |  (this process)   | HTTP |  (DeepSeek etc.)|
+---------------------+      +-------------------+      +-----------------+
                                      |
                                      v
                                providers.json
                                (atomic disk writes)
```

Three provider adapters in `src/adapters/`:

- `openai.ts` — OpenAI, DeepSeek, Ollama, LM Studio, vLLM, etc.
- `anthropic.ts` — Anthropic Messages API (different shape)
- `gemini.ts` — Google Gemini (third shape)

Each adapter translates requests/responses in isolation. A bug in the
Gemini adapter cannot break OpenAI-format traffic.

Two-tier memory in `src/core/memory.ts`:

1. Rolling window: keep the last N turns verbatim.
2. When history exceeds the threshold, summarize the older turns via
   a configurable summarizer provider/model and prepend as a system note.

This prevents the two failure modes of naive truncation: hard
context-limit crashes and silent quality collapse from cutting off
mid-instruction.

Idle timer in `src/core/idle-timer.ts`:

- Bumped on every completed request.
- After 12 minutes of no activity (configurable), the server logs a
  shutdown reason and exits with code 0.
- Persists a heartbeat file so a child that starts after its parent
  crashed can detect the stale state and surface it.

## License

MIT — same as Kodrix.
