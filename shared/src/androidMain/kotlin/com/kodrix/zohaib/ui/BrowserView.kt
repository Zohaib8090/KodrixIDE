package com.kodrix.zohaib.ui

import android.webkit.*
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.kodrix.zohaib.viewmodel.TerminalViewModel

@Composable
fun BrowserView(viewModel: TerminalViewModel) {
    val browserUrlState by viewModel.browserUrl.collectAsState()
    var url by remember { mutableStateOf(browserUrlState) }
    var webView: WebView? by remember { mutableStateOf(null) }
    var isDesktopMode by remember { mutableStateOf(false) }
    val uiScale by viewModel.uiScale.collectAsState()
    
    // File Picker State
    var filePathCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        filePathCallback?.onReceiveValue(uris.toTypedArray())
        filePathCallback = null
    }

    // Sync state when changed from outside
    LaunchedEffect(browserUrlState) {
        if (browserUrlState != url) {
            url = browserUrlState
            webView?.loadUrl(url)
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF0D1117))) {
        // Browser Toolbar
        Row(
            modifier = Modifier.fillMaxWidth().height((40 * uiScale).dp).background(Color(0xFF161B22)).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { webView?.goBack() }, modifier = Modifier.size((24 * uiScale).dp)) {
                Icon(Icons.Default.ArrowBack, null, tint = Color.Gray, modifier = Modifier.size((16 * uiScale).dp))
            }
            IconButton(onClick = { webView?.goForward() }, modifier = Modifier.size((24 * uiScale).dp)) {
                Icon(Icons.Default.ArrowForward, null, tint = Color.Gray, modifier = Modifier.size((16 * uiScale).dp))
            }
            IconButton(onClick = { webView?.reload() }, modifier = Modifier.size((24 * uiScale).dp)) {
                Icon(Icons.Default.Refresh, null, tint = Color.Gray, modifier = Modifier.size((16 * uiScale).dp))
            }
            
            Spacer(Modifier.width(4.dp))
            
            // Zoom Controls
            IconButton(onClick = { webView?.zoomOut() }, modifier = Modifier.size((24 * uiScale).dp)) {
                Icon(Icons.Default.Remove, "Zoom Out", tint = Color.Gray, modifier = Modifier.size((16 * uiScale).dp))
            }
            IconButton(onClick = { webView?.zoomIn() }, modifier = Modifier.size((24 * uiScale).dp)) {
                Icon(Icons.Default.Add, "Zoom In", tint = Color.Gray, modifier = Modifier.size((16 * uiScale).dp))
            }
            
            Spacer(Modifier.width(4.dp))

            BasicTextField(
                value = url,
                onValueChange = { url = it },
                modifier = Modifier.weight(1f).background(Color(0xFF0D1117), androidx.compose.foundation.shape.RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
                textStyle = TextStyle(color = Color.White, fontSize = (12 * uiScale).sp, fontFamily = FontFamily.Monospace),
                cursorBrush = SolidColor(Color(0xFF58A6FF)),
                singleLine = true,
                decorationBox = { innerTextField ->
                    if (url.isEmpty()) Text("Enter URL...", color = Color.Gray, fontSize = (12 * uiScale).sp)
                    innerTextField()
                }
            )

            Spacer(Modifier.width(8.dp))

            // Desktop Mode Toggle
            IconButton(
                onClick = { 
                    isDesktopMode = !isDesktopMode
                    webView?.settings?.apply {
                        val desktopUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                        val mobileUA = WebSettings.getDefaultUserAgent(webView?.context)
                        userAgentString = if (isDesktopMode) desktopUA else mobileUA
                        useWideViewPort = isDesktopMode
                        loadWithOverviewMode = isDesktopMode
                    }
                    webView?.reload()
                }, 
                modifier = Modifier.size((24 * uiScale).dp)
            ) {
                Icon(
                    if (isDesktopMode) Icons.Default.DesktopWindows else Icons.Default.Smartphone, 
                    "Desktop Mode", 
                    tint = if (isDesktopMode) Color(0xFF58A6FF) else Color.Gray, 
                    modifier = Modifier.size((16 * uiScale).dp)
                )
            }

            // DevTools Toggle
            IconButton(
                onClick = { 
                    webView?.evaluateJavascript("if(window.eruda) { if(eruda._isInit) { eruda.show() } else { eruda.init(); eruda.show() } }", null)
                }, 
                modifier = Modifier.size((24 * uiScale).dp)
            ) {
                Icon(Icons.Default.Terminal, "Open DevTools", tint = Color(0xFF238636), modifier = Modifier.size((16 * uiScale).dp))
            }

            IconButton(onClick = { webView?.loadUrl(if (url.startsWith("http")) url else "http://$url") }, modifier = Modifier.size((24 * uiScale).dp)) {
                Icon(Icons.Default.PlayArrow, null, tint = Color(0xFF58A6FF), modifier = Modifier.size((16 * uiScale).dp))
            }

            Spacer(Modifier.width(4.dp))

            // Close Browser Button
            IconButton(
                onClick = { viewModel.closeBrowser() },
                modifier = Modifier.size((24 * uiScale).dp)
            ) {
                Icon(Icons.Default.Close, "Close Browser", tint = Color(0xFFF85149), modifier = Modifier.size((18 * uiScale).dp))
            }
        }
        
        HorizontalDivider(color = Color(0xFF30363D))

        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    webView = this
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        allowFileAccess = true
                        allowContentAccess = true
                        setSupportZoom(true)
                        builtInZoomControls = true
                        displayZoomControls = false
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    }
                    
                    // Enable Remote Debugging via PC Chrome only in debug builds
                    val isDebuggable = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                    WebView.setWebContentsDebuggingEnabled(isDebuggable)

                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val nextUrl = request?.url?.toString() ?: return false
                            if (nextUrl.startsWith("file://") || nextUrl.startsWith("javascript:") || nextUrl.startsWith("intent://")) {
                                viewModel.appendLogcat("BROWSER [SECURITY]: Blocked unsafe URL scheme: ${request.url.scheme}")
                                return true
                            }
                            return false
                        }

                        override fun onPageFinished(view: WebView?, urlStr: String?) {
                            urlStr?.let { url = it }
                            // Inject Eruda DevTools
                            val injectScript = """
                                (function () {
                                    if (window.eruda) return;
                                    var script = document.createElement('script');
                                    script.src = '//cdn.jsdelivr.net/npm/eruda';
                                    document.body.appendChild(script);
                                    script.onload = function () {
                                        eruda.init({
                                            theme: 'dark',
                                            defaults: {
                                                displaySize: 50,
                                                transparency: 0.9,
                                                theme: 'Material Dark'
                                            }
                                        });
                                    };
                                })();
                            """.trimIndent()
                            view?.evaluateJavascript(injectScript, null)

                            // Inject JS Console Bridge
                            val bridgeJs = """
                                (function() {
                                    var oldLog = console.log;
                                    var oldError = console.error;
                                    var oldWarn = console.warn;
                                    console.log = function() {
                                        IDEBridge.log(Array.from(arguments).join(' '));
                                        oldLog.apply(console, arguments);
                                    };
                                    console.error = function() {
                                        IDEBridge.error(Array.from(arguments).join(' '));
                                        oldError.apply(console, arguments);
                                    };
                                    console.warn = function() {
                                        IDEBridge.warn(Array.from(arguments).join(' '));
                                        oldWarn.apply(console, arguments);
                                    };
                                })();
                            """.trimIndent()
                            view?.evaluateJavascript(bridgeJs, null)
                        }

                        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                            viewModel.appendLogcat("BROWSER [ERROR]: Failed to load ${request?.url} - ${error?.description} (${error?.errorCode})")
                        }
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onPermissionRequest(request: PermissionRequest?) {
                            request?.grant(request.resources)
                        }

                        override fun onShowFileChooser(
                            webView: WebView?,
                            callback: ValueCallback<Array<Uri>>?,
                            fileChooserParams: FileChooserParams?
                        ): Boolean {
                            filePathCallback = callback
                            filePickerLauncher.launch("*/*")
                            return true
                        }
                    }

                    // Console Bridge - Scoped to trusted origins
                    addJavascriptInterface(object {
                        private fun isAllowedOrigin(): Boolean {
                            val currentUrl = webView?.url ?: return false
                            return currentUrl.startsWith("http://localhost") || 
                                   currentUrl.startsWith("http://127.0.0.1") ||
                                   currentUrl.startsWith("file:///android_asset/")
                        }

                        @JavascriptInterface
                        fun log(msg: String) { 
                            if (isAllowedOrigin()) viewModel.appendLogcat("BROWSER [LOG]: $msg") 
                        }
                        @JavascriptInterface
                        fun error(msg: String) { 
                            if (isAllowedOrigin()) viewModel.appendLogcat("BROWSER [ERROR]: $msg") 
                        }
                        @JavascriptInterface
                        fun warn(msg: String) { 
                            if (isAllowedOrigin()) viewModel.appendLogcat("BROWSER [WARN]: $msg") 
                        }
                    }, "IDEBridge")

                    loadUrl(url)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        DisposableEffect(Unit) {
            onDispose {
                webView?.apply {
                    clearHistory()
                    clearCache(true)
                    loadUrl("about:blank")
                    onPause()
                    removeAllViews()
                    destroy()
                }
                webView = null
            }
        }
    }
}
