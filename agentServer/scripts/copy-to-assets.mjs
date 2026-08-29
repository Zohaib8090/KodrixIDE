#!/usr/bin/env node
/**
 * Copy the bundled agent server into the Android assets directory.
 * Run as: node scripts/copy-to-assets.mjs
 *
 * The launcher (AgentServerLauncher.kt) extracts this from
 * `assets/agentServer/server.bundled.js` into filesDir/agentServer/
 * on first use, then runs it via the bundled libnode_bin.so.
 */
import { copyFile, mkdir } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);

const src = resolve(__dirname, "..", "dist", "server.bundled.js");
const dest = resolve(__dirname, "..", "..", "androidApp", "src", "main", "assets", "agentServer", "server.bundled.js");

await mkdir(dirname(dest), { recursive: true });
await copyFile(src, dest);
console.log(`[copy-to-assets] ${src} -> ${dest}`);
