/**
 * Idle timer: self-terminate the server if no request has arrived for a
 * while. Combined with the IDE spawning the server on demand, this means:
 *
 *   - Zero idle memory/CPU when the user isn't using the agent
 *   - If the IDE crashes or force-quits without killing its child,
 *     the agent process won't linger as a zombie eating resources
 *
 * We persist a heartbeat to data/idle-timer.json so a child that restarts
 * after the parent crashed can know how stale its last activity was.
 */
import { promises as fs } from "node:fs";
import path from "node:path";
import { DATA_DIR } from "./providers.js";

const HEARTBEAT_FILE = path.join(DATA_DIR, "idle-timer.json");
const DEFAULT_TIMEOUT_MS = 12 * 60 * 1000; // 12 minutes

export interface IdleTimerOptions {
  timeoutMs?: number;
  onTimeout: () => void;
}

export class IdleTimer {
  private timer: NodeJS.Timeout | null = null;
  private lastActivity = Date.now();
  private readonly timeoutMs: number;
  private readonly onTimeout: () => void;
  private readonly writeHeartbeat: boolean;

  constructor(opts: IdleTimerOptions) {
    this.timeoutMs = opts.timeoutMs ?? DEFAULT_TIMEOUT_MS;
    this.onTimeout = opts.onTimeout;
    this.writeHeartbeat = true;
  }

  /**
   * Start the timer. If a heartbeat file exists from a previous run and
   * is older than the timeout, terminate immediately (we're a leftover).
   */
  async start(): Promise<void> {
    await this.checkForZombie();
    this.bump();
  }

  /** Call this on every incoming request to keep the process alive. */
  bump(): void {
    this.lastActivity = Date.now();
    if (this.timer) clearTimeout(this.timer);
    this.timer = setTimeout(() => {
      const idle = Date.now() - this.lastActivity;
      if (idle >= this.timeoutMs) {
        // Persist a final heartbeat marking the shutdown so a parent
        // inspecting the file knows it was a clean exit, not a crash.
        this.persistHeartbeat().finally(() => this.onTimeout());
      } else {
        // Not actually idle (clock skew or earlier than expected). Re-arm.
        this.bump();
      }
    }, this.timeoutMs);
    if (this.writeHeartbeat) {
      // Fire-and-forget; failures are non-fatal.
      this.persistHeartbeat().catch(() => undefined);
    }
  }

  stop(): void {
    if (this.timer) clearTimeout(this.timer);
    this.timer = null;
  }

  private async persistHeartbeat(): Promise<void> {
    try {
      await fs.mkdir(DATA_DIR, { recursive: true });
      const tmp = path.join(DATA_DIR, `.idle-timer.${process.pid}.${Date.now()}.tmp`);
      await fs.writeFile(
        tmp,
        JSON.stringify({ pid: process.pid, lastActivity: this.lastActivity, timeoutMs: this.timeoutMs }),
        "utf8"
      );
      await fs.rename(tmp, HEARTBEAT_FILE);
    } catch {
      // Heartbeat is best-effort.
    }
  }

  private async checkForZombie(): Promise<void> {
    try {
      const raw = await fs.readFile(HEARTBEAT_FILE, "utf8");
      const data = JSON.parse(raw) as { lastActivity: number };
      const age = Date.now() - data.lastActivity;
      if (age >= this.timeoutMs) {
        // Stale heartbeat from a previous run — that's us if we crashed
        // and got restarted. Log it; don't auto-kill (could be a fresh
        // legit start). The parent IDE can read the file if it cares.
        // eslint-disable-next-line no-console
        console.warn(
          `[idle-timer] stale heartbeat found (${Math.floor(age / 1000)}s old); proceeding`
        );
      }
    } catch {
      // No file or unreadable — first start, nothing to do.
    }
  }
}
