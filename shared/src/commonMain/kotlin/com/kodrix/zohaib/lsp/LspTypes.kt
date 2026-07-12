package com.kodrix.zohaib.lsp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// Basic JSON-RPC 2.0 structures
data class JsonRpcRequest<T>(
    val jsonrpc: String = "2.0",
    val id: Int,
    val method: String,
    val params: T
)

data class JsonRpcNotification<T>(
    val jsonrpc: String = "2.0",
    val method: String,
    val params: T
)

data class JsonRpcResponse<T>(
    val jsonrpc: String = "2.0",
    val id: Int,
    val result: T?,
    val error: JsonRpcError? = null
)

data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: Any? = null
)

// Initialize
data class InitializeParams(
    val processId: Int? = null,
    val rootUri: String? = null,
    val capabilities: ClientCapabilities = ClientCapabilities()
)

data class ClientCapabilities(
    val textDocument: TextDocumentClientCapabilities = TextDocumentClientCapabilities()
)

data class TextDocumentClientCapabilities(
    val synchronization: SynchronizationCapabilities = SynchronizationCapabilities(),
    val completion: CompletionCapabilities = CompletionCapabilities(),
    val publishDiagnostics: PublishDiagnosticsCapabilities = PublishDiagnosticsCapabilities(),
    val hover: HoverCapabilities = HoverCapabilities()
)

data class SynchronizationCapabilities(
    val didSave: Boolean = true
)

data class CompletionCapabilities(
    val completionItem: CompletionItemCapabilities = CompletionItemCapabilities(),
    val contextSupport: Boolean = true
)

data class CompletionItemCapabilities(
    val snippetSupport: Boolean = true,
    val commitCharactersSupport: Boolean = true
)

data class PublishDiagnosticsCapabilities(
    val relatedInformation: Boolean = true,
    val versionSupport: Boolean = false,
    val codeDescriptionSupport: Boolean = true
)

data class HoverCapabilities(
    val contentFormat: List<String> = listOf("plaintext")
)

// Document Sync
data class DidOpenTextDocumentParams(
    val textDocument: TextDocumentItem
)

data class TextDocumentItem(
    val uri: String,
    val languageId: String,
    val version: Int,
    val text: String
)

data class DidChangeTextDocumentParams(
    val textDocument: VersionedTextDocumentIdentifier,
    val contentChanges: List<TextDocumentContentChangeEvent>
)

data class VersionedTextDocumentIdentifier(
    val uri: String,
    val version: Int
)

data class TextDocumentContentChangeEvent(
    val text: String
)

// Diagnostics
data class PublishDiagnosticsParams(
    val uri: String,
    val diagnostics: List<Diagnostic>
)

data class Diagnostic(
    val range: Range,
    val severity: Int? = null,
    val code: String? = null,
    val source: String? = null,
    val message: String
)

@Serializable
data class Range(
    val start: Position,
    val end: Position
)

@Serializable
data class Position(
    val line: Int,
    val character: Int
)

// Completion
data class CompletionContext(
    val triggerKind: Int, // 1=Invoked, 2=TriggerCharacter, 3=TriggerForIncompleteCompletions
    val triggerCharacter: String? = null
)

data class CompletionParams(
    val textDocument: TextDocumentIdentifier,
    val position: Position,
    val context: CompletionContext? = null
)

data class TextDocumentIdentifier(
    val uri: String
)

@Serializable
data class CompletionList(
    val isIncomplete: Boolean,
    val items: List<CompletionItem>
)

@Serializable
data class CompletionItem(
    val label: String,
    val kind: Int? = null,
    val detail: String? = null,
    val documentation: String? = null,
    val sortText: String? = null,
    val filterText: String? = null,
    val insertText: String? = null,
    val textEdit: TextEdit? = null,
    val additionalTextEdits: List<TextEdit>? = null
)

@Serializable
data class TextEdit(
    val range: Range,
    val newText: String
)

@Serializable
data class MarkupContent(
    val kind: String,
    val value: String
)

// Hover
data class HoverParams(
    val textDocument: TextDocumentIdentifier,
    val position: Position
)

data class HoverResult(
    val contents: JsonElement? = null,
    val range: Range? = null
)

// Go-to-Definition
data class DefinitionParams(
    val textDocument: TextDocumentIdentifier,
    val position: Position
)

data class Location(
    val uri: String,
    val range: Range
)

// Code Actions
data class CodeActionParams(
    val textDocument: TextDocumentIdentifier,
    val range: Range,
    val context: CodeActionContext
)

data class CodeActionContext(
    val diagnostics: List<Diagnostic> = emptyList()
)
