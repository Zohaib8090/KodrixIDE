package com.kodrix.zohaib.runtime

import org.json.JSONObject

/**
 * Languages Kodrix knows how to set up without the online registry: which Termux
 * packages to install, which commands to expose, which file types they handle and how
 * to start their language server. Same schema as the registry's `versions.json`; a
 * registry entry with the same id replaces the one here.
 *
 * Versions are never listed: each entry names packages, and the version shown (and
 * any update) comes from the live Termux package index. So this table only changes
 * when a new *language* is added, never for new releases. Anything not listed can still
 * be installed from "All packages" in Marketplace → Runtimes.
 *
 * `%install%` becomes `${install}` (the install dir) and `%node%` becomes `${node}`
 * (the Node.js built into the app) when parsed.
 */
object BuiltinCatalog {

    private const val ICONS = "https://raw.githubusercontent.com/Zohaib8090/KodrixMarketplace/main/icons/"

    private val JSON = """
    {
      "node": {
        "displayName": "Node.js", "category": "Runtime", "icon": "node.png",
        "description": "JavaScript runtime. One is built into Kodrix; install another only if a project needs a specific version.",
        "binaries": ["node", "npm", "npx"],
        "verify": { "command": ["%install%/bin/node", "--version"] },
        "versions": [
          { "tag": "Latest", "packages": ["nodejs"] },
          { "tag": "LTS", "packages": ["nodejs-lts"] }
        ]
      },
      "python": {
        "displayName": "Python", "category": "Runtime", "icon": "python.png",
        "description": "Python 3 with pip. Autocomplete by Pyright.",
        "languages": { "py": "python", "pyw": "python", "pyi": "python" },
        "binaries": ["python", "python3", "pip", "pip3"],
        "env": { "PYTHONHOME": "%install%" },
        "verify": { "command": ["%install%/bin/python3", "--version"] },
        "lsp": { "command": ["%node%", "%install%/lsp/node_modules/pyright/langserver.index.js", "--stdio"], "npm": ["pyright"] },
        "versions": [ { "packages": ["python", "python-pip"], "versionPackage": "python" } ]
      },
      "c": {
        "displayName": "C / C++ (Clang)", "category": "Compiler", "icon": "c.png",
        "description": "Clang compiler with the Android headers. Autocomplete by clangd. Large download.",
        "languages": { "c": "c", "h": "c", "cpp": "cpp", "cc": "cpp", "cxx": "cpp", "hpp": "cpp", "hh": "cpp", "hxx": "cpp" },
        "binaries": ["clang", "clang++", "cc=clang", "c++=clang++", "clangd", "lld", "ld.lld"],
        "lsp": { "command": ["%install%/bin/clangd"] },
        "verify": { "command": ["%install%/bin/clang", "--version"] },
        "versions": [ { "packages": ["clang", "ndk-sysroot"], "versionPackage": "clang" } ]
      },
      "rust": {
        "displayName": "Rust", "category": "Compiler", "icon": "rust.png",
        "description": "rustc and cargo. Autocomplete by rust-analyzer. Large download.",
        "languages": { "rs": "rust" },
        "binaries": ["rustc", "cargo", "rustdoc", "rust-analyzer"],
        "lsp": { "command": ["%install%/bin/rust-analyzer"] },
        "verify": { "command": ["%install%/bin/rustc", "--version"] },
        "versions": [ { "packages": ["rust", "rust-src", "rust-analyzer"], "versionPackage": "rust" } ]
      },
      "go": {
        "displayName": "Go (experimental)", "category": "Compiler", "icon": "go.png",
        "description": "Go toolchain with gopls. Experimental: Go starts programs without the C library, which Android blocks for downloaded tools, so `go build`, `go run` and some gopls features may fail.",
        "languages": { "go": "go" },
        "binaries": ["go", "gofmt", "gopls"],
        "env": { "GOROOT": "%install%/lib/go", "GOPATH": "%install%/gopath", "GOTOOLCHAIN": "local", "GOTELEMETRY": "off" },
        "lsp": { "command": ["%install%/bin/gopls"] },
        "verify": { "command": ["%install%/bin/go", "version"] },
        "versions": [ { "packages": ["golang", "gopls"], "versionPackage": "golang" } ]
      },
      "lua": {
        "displayName": "Lua", "category": "Runtime", "icon": "lua.png",
        "description": "Lua 5.4. Autocomplete by lua-language-server.",
        "languages": { "lua": "lua" },
        "binaries": ["lua=lua5.4", "luac=luac5.4", "lua5.4", "luac5.4"],
        "lsp": { "command": ["%install%/share/lua-language-server/bin/lua-language-server", "--logpath=%install%/tmp/lua-ls/log", "--metapath=%install%/tmp/lua-ls/meta"] },
        "verify": { "command": ["%install%/bin/lua5.4", "-v"] },
        "versions": [ { "packages": ["lua54", "lua-language-server"], "versionPackage": "lua54" } ]
      },
      "php": {
        "displayName": "PHP", "category": "Runtime", "icon": "php.png",
        "description": "PHP interpreter. Autocomplete by Intelephense.",
        "languages": { "php": "php" },
        "binaries": ["php"],
        "lsp": { "command": ["%node%", "%install%/lsp/node_modules/intelephense/lib/intelephense.js", "--stdio"], "npm": ["intelephense"] },
        "verify": { "command": ["%install%/bin/php", "--version"] },
        "versions": [ { "packages": ["php"] } ]
      },
      "zig": {
        "displayName": "Zig (experimental)", "category": "Compiler", "icon": "zig.png",
        "description": "Zig compiler. Autocomplete by zls. Experimental on Android.",
        "languages": { "zig": "zig", "zon": "zig" },
        "binaries": ["zig", "zls"],
        "lsp": { "command": ["%install%/bin/zls"] },
        "verify": { "command": ["%install%/bin/zig", "version"] },
        "versions": [ { "packages": ["zig", "zls"], "versionPackage": "zig" } ]
      },
      "dart": {
        "displayName": "Dart", "category": "Runtime", "icon": "dart.png",
        "description": "Dart SDK. Autocomplete by Dart's own analysis server.",
        "languages": { "dart": "dart" },
        "binaries": ["dart"],
        "lsp": { "command": ["%install%/bin/dart", "language-server", "--protocol=lsp"] },
        "versions": [ { "packages": ["dart"] } ]
      },
      "gleam": {
        "displayName": "Gleam", "category": "Compiler", "icon": "gleam.png",
        "description": "Gleam compiler with Erlang. Autocomplete by Gleam's own language server.",
        "languages": { "gleam": "gleam" },
        "binaries": ["gleam", "erl", "escript"],
        "lsp": { "command": ["%install%/bin/gleam", "lsp"] },
        "verify": { "command": ["%install%/bin/gleam", "--version"] },
        "versions": [ { "packages": ["gleam", "erlang"], "versionPackage": "gleam" } ]
      },
      "swift": {
        "displayName": "Swift", "category": "Compiler", "icon": "swift.png",
        "description": "Swift compiler. Autocomplete by sourcekit-lsp. Very large download.",
        "languages": { "swift": "swift" },
        "binaries": ["swift", "swiftc", "sourcekit-lsp"],
        "lsp": { "command": ["%install%/bin/sourcekit-lsp"] },
        "versions": [ { "packages": ["swift", "sourcekit-lsp"], "versionPackage": "swift" } ]
      },
      "ruby": {
        "displayName": "Ruby", "category": "Runtime", "icon": "ruby.png",
        "description": "Ruby with gem and irb. Runs in the terminal; no autocomplete server is available for Android yet.",
        "languages": { "rb": "ruby" },
        "binaries": ["ruby", "gem", "irb", "bundle", "bundler", "rake"],
        "verify": { "command": ["%install%/bin/ruby", "--version"] },
        "versions": [ { "packages": ["ruby"] } ]
      },
      "java": {
        "displayName": "Java (OpenJDK 21)", "category": "Compiler", "icon": "java.png",
        "description": "java, javac and jshell. Runs in the terminal; no autocomplete server is available for Android yet. Large download.",
        "languages": { "java": "java" },
        "binaries": ["java", "javac", "jar", "jshell"],
        "versions": [ { "packages": ["openjdk-21"] } ]
      },
      "kotlin": {
        "displayName": "Kotlin", "category": "Compiler", "icon": "kotlin.png",
        "description": "Kotlin compiler (needs Java, installed with it). Runs in the terminal; no autocomplete server is available for Android yet.",
        "languages": { "kt": "kotlin", "kts": "kotlin" },
        "binaries": ["kotlin", "kotlinc", "java", "javac"],
        "versions": [ { "packages": ["kotlin"] } ]
      },
      "perl": {
        "displayName": "Perl", "category": "Runtime", "icon": "perl.png",
        "description": "Perl 5 with cpan. Runs in the terminal.",
        "languages": { "pl": "perl", "pm": "perl" },
        "binaries": ["perl", "cpan"],
        "verify": { "command": ["%install%/bin/perl", "--version"] },
        "versions": [ { "packages": ["perl"] } ]
      },
      "elixir": {
        "displayName": "Elixir", "category": "Runtime", "icon": "elixir.png",
        "description": "Elixir with mix and iex (includes Erlang). Runs in the terminal.",
        "languages": { "ex": "elixir", "exs": "elixir" },
        "binaries": ["elixir", "elixirc", "iex", "mix", "erl"],
        "versions": [ { "packages": ["elixir"] } ]
      },
      "haskell": {
        "displayName": "Haskell (GHC)", "category": "Compiler", "icon": "haskell.png",
        "description": "GHC compiler and GHCi. Runs in the terminal. Large download.",
        "languages": { "hs": "haskell" },
        "binaries": ["ghc", "ghci", "runghc"],
        "versions": [ { "packages": ["ghc"] } ]
      },
      "nim": {
        "displayName": "Nim", "category": "Compiler", "icon": "nim.png",
        "description": "Nim compiler with nimble. Runs in the terminal.",
        "languages": { "nim": "nim" },
        "binaries": ["nim", "nimble"],
        "versions": [ { "packages": ["nim"] } ]
      },
      "crystal": {
        "displayName": "Crystal", "category": "Compiler", "icon": "crystal.png",
        "description": "Crystal compiler with shards. Runs in the terminal.",
        "languages": { "cr": "crystal" },
        "binaries": ["crystal", "shards"],
        "versions": [ { "packages": ["crystal"] } ]
      },
      "deno": {
        "displayName": "Deno", "category": "Runtime", "icon": "deno.png",
        "description": "Alternative JavaScript/TypeScript runtime. Editing .js/.ts keeps using the built-in autocomplete.",
        "binaries": ["deno"],
        "versions": [ { "packages": ["deno"] } ]
      },
      "bun": {
        "displayName": "Bun", "category": "Runtime", "icon": "bun.png",
        "description": "Fast JavaScript runtime and package manager. Editing .js/.ts keeps using the built-in autocomplete.",
        "binaries": ["bun", "bunx"],
        "versions": [ { "packages": ["bun"] } ]
      },
      "markdown": {
        "displayName": "Markdown support", "category": "Language support", "icon": "markdown.png",
        "description": "Autocomplete for links and headings in .md files (marksman).",
        "languages": { "md": "markdown", "markdown": "markdown" },
        "binaries": ["marksman"],
        "lsp": { "command": ["%install%/bin/marksman", "server"] },
        "versions": [ { "packages": ["marksman"] } ]
      },
      "toml": {
        "displayName": "TOML support", "category": "Language support", "icon": "toml.png",
        "description": "Validation and autocomplete for .toml files (taplo).",
        "languages": { "toml": "toml" },
        "binaries": ["taplo"],
        "lsp": { "command": ["%install%/bin/taplo", "lsp", "stdio"] },
        "versions": [ { "packages": ["taplo"] } ]
      }
    }
    """

    /** Tool id → registry-schema entry, ready to merge under the online registry. */
    val tools: Map<String, JSONObject> by lazy {
        val root = JSONObject(JSON.replace("%install%", "\${install}").replace("%node%", "\${node}"))
        val out = LinkedHashMap<String, JSONObject>()
        root.keys().forEach { id ->
            val t = root.getJSONObject(id)
            t.optString("icon").takeIf { it.isNotEmpty() }?.let { t.put("iconUrl", ICONS + it) }
            val versions = t.optJSONArray("versions")
            if (versions != null) {
                for (i in 0 until versions.length()) {
                    val v = versions.getJSONObject(i)
                    v.put("source", "termux")
                    if (!v.has("tag")) v.put("tag", "Latest")
                }
            }
            out[id] = t
        }
        out
    }
}
