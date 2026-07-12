package com.kodrix.zohaib.beta

/**
 * ╔══════════════════════════════════════════════════════════════╗
 * ║                  KODRIX BETA FEATURE FLAGS                   ║
 * ╠══════════════════════════════════════════════════════════════╣
 * ║  Add new experimental / in-progress features here.           ║
 * ║                                                              ║
 * ║  HOW IT WORKS:                                               ║
 * ║  • Each flag is a const val (compile-time).                  ║
 * ║  • Set it to `true`  → feature is testable when the user     ║
 * ║    enables "Beta Mode" in Settings.                          ║
 * ║  • Set it to `false` → feature is fully hidden for everyone. ║
 * ║                                                              ║
 * ║  Beta Mode toggle is in: Settings → Developer → Beta Mode    ║
 * ║                                                              ║
 * ║  When a feature graduates out of beta:                       ║
 * ║    1. Remove its flag from this file.                        ║
 * ║    2. Remove the `isBetaEnabled &&` gate in the UI.          ║
 * ╚══════════════════════════════════════════════════════════════╝
 */
object BetaFlags {

    // ── Marketplace / Runtimes ───────────────────────────────────────────────

    /**
     * Node.js 26.2.0 download via Marketplace.
     *
     * Status: UNDER TEST — binary fails on some devices due to missing libffi.so.
     * The remote ZIP needs to be updated with the patched lib before GA.
     */
    const val RUNTIME_NODE_26 = true

    /**
     * Node.js 24.15.0 (LTS Jod) download via Marketplace.
     *
     * Status: STABLE — ready for GA. Remove flag and gate when shipped.
     */
    const val RUNTIME_NODE_24_LTS = true

    // ── AI / Language Server ─────────────────────────────────────────────────

    /**
     * Inline AI code completions inside the editor.
     *
     * Status: NOT STARTED — reserved for future implementation.
     */
    const val AI_INLINE_COMPLETIONS = false

    // ── Terminal ─────────────────────────────────────────────────────────────

    /**
     * Multiple simultaneous terminal sessions (tab bar).
     *
     * Status: UNDER TEST — session switching has edge cases.
     */
    const val TERMINAL_MULTI_SESSION = false

    // ── Source Control ───────────────────────────────────────────────────────

    /**
     * Visual diff viewer inside the IDE.
     *
     * Status: NOT STARTED — reserved for future implementation.
     */
    const val SOURCE_CONTROL_DIFF_VIEWER = false

    // ─────────────────────────────────────────────────────────────────────────
    // Add new flags above this line. Keep the groupings tidy.
    // ─────────────────────────────────────────────────────────────────────────
}
