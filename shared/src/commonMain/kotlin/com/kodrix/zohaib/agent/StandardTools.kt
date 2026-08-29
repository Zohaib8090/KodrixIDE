package com.kodrix.zohaib.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The 10 starter tools the agent can call. Each is a tiny class that
 * implements [Tool] with a hard-coded schema + executor.
 *
 * Safe tools (read-only) can be auto-approved in auto-run mode; unsafe
 * tools (write/exec) always ask the user.
 */
object StandardTools {

    // ---- READ-ONLY (auto-approve) ----

    class ReadFileTool : Tool {
        override val name = "read_file"
        override val description = "Read the contents of a file at the given path (relative to project root, or absolute)."
        override val safe = true
        override val parametersSchema = Schemas.objectSchema(
            mapOf("path" to mapOf("type" to "string", "description" to "File path to read"))
        )
        override suspend fun execute(args: JsonObject, context: ToolContext): String =
            context.readFile(args.requireString("path"))
    }

    class ListDirTool : Tool {
        override val name = "list_dir"
        override val description = "List files and subdirectories at the given path. Returns absolute paths."
        override val safe = true
        override val parametersSchema = Schemas.objectSchema(
            mapOf(
                "path" to mapOf("type" to "string", "description" to "Directory path; default is project root"),
                "max_depth" to mapOf("type" to "integer", "description" to "Recursion depth; 1 = one level", "default" to "1"),
            )
        )
        override suspend fun execute(args: JsonObject, context: ToolContext): String {
            val path = args.optionalString("path", ".")
            val depth = args.optionalInt("max_depth", 1)
            val entries = context.listDir(path, depth)
            return if (entries.isEmpty()) "(empty)" else entries.joinToString("\n")
        }
    }

    class GrepTool : Tool {
        override val name = "grep"
        override val description = "Search files for a regex pattern. Returns matching lines as `path:line:text`."
        override val safe = true
        override val parametersSchema = Schemas.objectSchema(
            mapOf(
                "pattern" to mapOf("type" to "string", "description" to "Regular expression to search for"),
                "glob" to mapOf("type" to "string", "description" to "Optional file glob, e.g. *.kt"),
                "path" to mapOf("type" to "string", "description" to "Directory to search; default is project root"),
            )
        )
        override suspend fun execute(args: JsonObject, context: ToolContext): String {
            val pattern = args.requireString("pattern")
            val glob = args.optionalString("glob").takeIf { it.isNotEmpty() }
            val path = args.optionalString("path").takeIf { it.isNotEmpty() }
            val matches = context.grep(pattern, glob, path)
            return if (matches.isEmpty()) "(no matches)" else matches.joinToString("\n")
        }
    }

    class LspDiagnosticsTool : Tool {
        override val name = "lsp_diagnostics"
        override val description = "Get syntax errors and warnings for a file from the language server."
        override val safe = true
        override val parametersSchema = Schemas.objectSchema(
            mapOf("path" to mapOf("type" to "string", "description" to "File path"))
        )
        override suspend fun execute(args: JsonObject, context: ToolContext): String =
            context.lspDiagnostics(args.requireString("path"))
    }

    class GitStatusTool : Tool {
        override val name = "git_status"
        override val description = "Show the current git status (modified/staged/untracked files)."
        override val safe = true
        override val parametersSchema = Schemas.objectSchema(emptyMap())
        override suspend fun execute(args: JsonObject, context: ToolContext): String =
            context.runGit(listOf("status"))
    }

    // ---- WRITE (always ask) ----

    class WriteFileTool : Tool {
        override val name = "write_file"
        override val description = "Create or overwrite a file. Creates parent directories as needed."
        override val parametersSchema = Schemas.objectSchema(
            mapOf(
                "path" to mapOf("type" to "string", "description" to "File path to write"),
                "content" to mapOf("type" to "string", "description" to "Full file content"),
            )
        )
        override suspend fun execute(args: JsonObject, context: ToolContext): String {
            val path = args.requireString("path")
            val content = args.requireString("content")
            context.writeFile(path, content)
            return "wrote $path (${content.length} bytes)"
        }
    }

    class EditFileTool : Tool {
        override val name = "edit_file"
        override val description = "Replace an exact string in a file. Fails if the old_string is not found."
        override val parametersSchema = Schemas.objectSchema(
            mapOf(
                "path" to mapOf("type" to "string", "description" to "File to edit"),
                "old_string" to mapOf("type" to "string", "description" to "Exact substring to find"),
                "new_string" to mapOf("type" to "string", "description" to "Replacement content"),
            )
        )
        override suspend fun execute(args: JsonObject, context: ToolContext): String {
            val path = args.requireString("path")
            val oldStr = args.requireString("old_string")
            val newStr = args.requireString("new_string")
            val current = context.readFile(path)
            if (!current.contains(oldStr)) {
                throw RuntimeException("old_string not found in $path (no exact match)")
            }
            val updated = current.replace(oldStr, newStr)
            context.writeFile(path, updated)
            return "edited $path (replaced ${oldStr.length} chars with ${newStr.length})"
        }
    }
    class RunShellTool : Tool {
        override val name = "run_shell"
        override val description = "Run a shell command in the project root. Returns combined stdout+stderr. Use with care."
        override val parametersSchema = Schemas.objectSchema(
            mapOf(
                "command" to mapOf("type" to "string", "description" to "Shell command to execute"),
                "timeout_ms" to mapOf("type" to "integer", "description" to "Optional timeout in ms; default 30000"),
            )
        )
        override suspend fun execute(args: JsonObject, context: ToolContext): String {
            val cmd = args.requireString("command")
            val timeout = args.optionalInt("timeout_ms", 30_000).toLong()
            return context.runShell(cmd, timeout)
        }
    }

    class GitCommitTool : Tool {
        override val name = "git_commit"
        override val description = "Stage all changes and commit with the given message."
        override val parametersSchema = Schemas.objectSchema(
            mapOf("message" to mapOf("type" to "string", "description" to "Commit message"))
        )
        override suspend fun execute(args: JsonObject, context: ToolContext): String {
            val msg = args.requireString("message")
            context.runGit(listOf("add", "-A"))
            return context.runGit(listOf("commit", "-m", msg))
        }
    }

    // ---- META (always ask) ----

    class AskUserTool : Tool {
        override val name = "ask_user"
        override val description = "Ask the user a multiple-choice question. Returns the selected option."
        override val parametersSchema = Schemas.objectSchema(
            mapOf(
                "question" to mapOf("type" to "string", "description" to "Question to ask"),
                "options" to mapOf("type" to "array", "description" to "List of 2-5 option labels"),
            )
        )
        override suspend fun execute(args: JsonObject, context: ToolContext): String {
            val question = args.requireString("question")
            // The `options` arg is an array; in this simple impl we just return
            // a string and let the UI prompt. The ToolContext's askUser returns
            // a String? synchronously; here we'd ideally use a suspend function
            // on the context but for v1 the UI surfaces the ask_user call as a
            // tool result and the user can reply via the chat.
            return "ASK: $question"
        }
    }

    /** All 10 tools in a stable order. */
    val all: List<Tool> = listOf(
        ReadFileTool(),
        ListDirTool(),
        GrepTool(),
        LspDiagnosticsTool(),
        GitStatusTool(),
        WriteFileTool(),
        EditFileTool(),
        RunShellTool(),
        GitCommitTool(),
        AskUserTool(),
    )
}
