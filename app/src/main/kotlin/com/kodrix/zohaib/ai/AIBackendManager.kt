package com.kodrix.zohaib.ai

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

class AIBackendManager(val application: Application) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var webView: WebView? = null
    private var isEngineReady = false
    private val askMutex = kotlinx.coroutines.sync.Mutex()

    private val _status = MutableStateFlow("Idle")
    val status = _status.asStateFlow()

    private var pendingUploadUris: Array<android.net.Uri>? = null

    fun startEngine(showBrowser: Boolean = false) {
        if (webView != null) return // Already started

        Handler(Looper.getMainLooper()).post {
            try {
                _status.value = "AI: Native Launch..."
                
                webView = WebView(application).apply {
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    }
                    
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onShowFileChooser(
                            webView: WebView?,
                            filePathCallback: android.webkit.ValueCallback<Array<android.net.Uri>>?,
                            fileChooserParams: FileChooserParams?
                        ): Boolean {
                            if (pendingUploadUris != null) {
                                filePathCallback?.onReceiveValue(pendingUploadUris)
                                pendingUploadUris = null
                            } else {
                                filePathCallback?.onReceiveValue(null)
                            }
                            return true
                        }
                    }

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            if (url?.contains("gemini.google.com") == true) {
                                isEngineReady = true
                                _status.value = "AI Engine: Ready"
                            }
                        }
                    }

                    loadUrl("https://gemini.google.com/app")
                }
            } catch (e: Exception) {
                Log.e("AIBackend", "Failed to start AI WebView", e)
                _status.value = "AI Engine Error: ${e.message}"
            }
        }
    }

    private suspend fun evaluateJavascriptSuspend(script: String): String? = suspendCancellableCoroutine { cont ->
        Handler(Looper.getMainLooper()).post {
            webView?.evaluateJavascript(script) { result ->
                if (cont.isActive) {
                    val unquoted = if (result != null && result != "null" && result.startsWith("\"") && result.endsWith("\"")) {
                        // Handle un-escaping JSON string formatting from evaluateJavascript
                        result.substring(1, result.length - 1)
                            .replace("\\\"", "\"")
                            .replace("\\n", "\n")
                            .replace("\\u003C", "<")
                            .replace("\\\\", "\\")
                    } else if (result == "null") {
                        null
                    } else {
                        result
                    }
                    cont.resume(unquoted)
                }
            } ?: cont.resume(null)
        }
    }

    suspend fun ask(prompt: String, fileUri: android.net.Uri? = null): String? = askMutex.withLock {
        withContext(Dispatchers.IO) {
        if (webView == null) {
            startEngine()
            delay(3000) // Give it some time to start loading
        }

        try {
            // Check if logged in by looking for the textbox
            var isLoggedIn = false
            for (i in 0..10) {
                val checkLoginScript = "document.querySelector('div[role=\"textbox\"]') !== null"
                val result = evaluateJavascriptSuspend(checkLoginScript)
                if (result == "true") {
                    isLoggedIn = true
                    break
                }
                delay(1000)
            }

            if (!isLoggedIn) {
                return@withContext "NEEDS_LOGIN"
            }
            
            var base64Image = ""
            var mimeType = "image/png"
            if (fileUri != null) {
                try {
                    mimeType = application.contentResolver.getType(fileUri) ?: "image/png"
                    val inputStream = application.contentResolver.openInputStream(fileUri)
                    val bytes = inputStream?.readBytes()
                    inputStream?.close()
                    if (bytes != null) {
                        base64Image = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                    }
                } catch (e: Exception) {
                    Log.e("AIBackend", "Failed to read image", e)
                }
            }

            // Safely pass the prompt to JS using Base64 to prevent any escaping issues with code files
            val base64Prompt = android.util.Base64.encodeToString(prompt.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
            
            val injectScript = """
                (function() {
                    const el = document.querySelector('div[role="textbox"]');
                    if (!el) return 'no_textbox';
                    
                    el.focus();
                    
                    // Clear existing text first
                    el.innerText = '';
                    el.dispatchEvent(new Event('input', { bubbles: true }));

                    // 1. Paste image if present
                    const b64Image = '$base64Image';
                    if (b64Image) {
                        try {
                            const byteCharacters = atob(b64Image);
                            const byteNumbers = new Array(byteCharacters.length);
                            for (let i = 0; i < byteCharacters.length; i++) {
                                byteNumbers[i] = byteCharacters.charCodeAt(i);
                            }
                            const byteArray = new Uint8Array(byteNumbers);
                            const blob = new Blob([byteArray], {type: '$mimeType'});
                            const file = new File([blob], 'attached_image.png', {type: '$mimeType'});
                            
                            const dataTransfer = new DataTransfer();
                            dataTransfer.items.add(file);
                            
                            const pasteEvent = new ClipboardEvent('paste', {
                                clipboardData: dataTransfer,
                                bubbles: true,
                                cancelable: true
                            });
                            el.dispatchEvent(pasteEvent);
                        } catch(e) { console.error('Paste error', e); }
                    }
                    
                    // 2. Wait for image to be attached, then insert prompt and send
                    setTimeout(() => {
                        const b64Prompt = '$base64Prompt';
                        if (b64Prompt) {
                            const bin = atob(b64Prompt);
                            const bytes = new Uint8Array(bin.length);
                            for (let i = 0; i < bin.length; i++) {
                                bytes[i] = bin.charCodeAt(i);
                            }
                            const decodedPrompt = new TextDecoder('utf-8').decode(bytes);
                            document.execCommand('insertText', false, decodedPrompt);
                        }
                        
                        setTimeout(() => {
                            const sendBtn = document.querySelector('button[aria-label="Send message"]');
                            if (sendBtn) sendBtn.click();
                        }, 1000);
                    }, b64Image ? 1500 : 100);
                    
                    return 'sent';
                })();
            """.trimIndent()
            
            // Count existing messages to detect the NEW one
            val countScript = "document.querySelectorAll('.message-content, .model-response-text').length"
            val initialCountStr = evaluateJavascriptSuspend(countScript) ?: "0"
            val initialCount = initialCountStr.toIntOrNull() ?: 0

            val injectResult = evaluateJavascriptSuspend(injectScript)
            if (injectResult == "no_textbox") {
                return@withContext "Error: Textbox not found."
            }

            // Polling for a NEW response
            var response: String? = null
            var stabilizedCount = 0
            var lastLength = 0
            
            val pollScript = """
                (function() {
                    const nodes = document.querySelectorAll('.message-content, .model-response-text');
                    if (nodes.length <= $initialCount) return 'WAITING'; 
                    const last = nodes[nodes.length - 1];
                    return last.innerText;
                })();
            """.trimIndent()

            // Wait a bit for the UI to register the send
            delay(2000)

            for (i in 0..120) { // 120 seconds timeout for complex agent tasks
                delay(1000)
                val currentText = evaluateJavascriptSuspend(pollScript)
                
                if (currentText != null && currentText != "WAITING" && currentText.isNotEmpty() && currentText != "null") {
                    if (currentText.length == lastLength && !currentText.endsWith("...")) {
                        stabilizedCount++
                        if (stabilizedCount >= 3) { // 3 seconds without change for safety
                            response = currentText
                            break
                        }
                    } else {
                        stabilizedCount = 0
                        lastLength = currentText.length
                    }
                }
            }

            return@withContext response ?: "Error: Timed out waiting for NEW response."

        } catch (e: Exception) {
            Log.e("AIBackend", "Request failed", e)
            return@withContext "Error: ${e.message}"
        }
    } }

    fun resetChat() {
        Handler(Looper.getMainLooper()).post {
            webView?.loadUrl("https://gemini.google.com/app")
        }
    }

    fun stopEngine() {
        Handler(Looper.getMainLooper()).post {
            webView?.destroy()
            webView = null
        }
        scope.cancel()
    }
}

