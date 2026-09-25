package com.kodrix.zohaib.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A language that works without installing anything from the Marketplace. */
private data class BuiltInLanguage(val name: String, val runs: String, val editor: String)

private val BUILT_IN = listOf(
    BuiltInLanguage("JavaScript", "Runs on the built-in Node.js", "Autocomplete, errors, go-to-definition"),
    BuiltInLanguage("TypeScript", "Runs on the built-in Node.js", "Autocomplete, errors, go-to-definition"),
    BuiltInLanguage("HTML · CSS · JSON", "Live preview in the browser panel", "Autocomplete and validation"),
    BuiltInLanguage("Shell (sh / bash)", "Runs in the built-in terminal", "Autocomplete and hover docs"),
    BuiltInLanguage("Git", "Built into the terminal and Source Control", "—"),
)

/**
 * First-run screen. Nothing is downloaded from here: it says what already works and
 * where to add more (Marketplace → Runtimes, which is driven by the online registry,
 * so new languages appear there without an app update).
 */
@Composable
fun WelcomeScreen(onOpenRuntimes: () -> Unit, onFinished: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF0D1117)),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier.widthIn(max = 560.dp).padding(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
            border = BorderStroke(1.dp, Color(0xFF30363D)),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
            ) {
                Text(
                    "Welcome to Kodrix",
                    color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "These work right away, with no downloads:",
                    color = Color(0xFF8B949E), fontSize = 14.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Spacer(Modifier.height(16.dp))

                BUILT_IN.forEach { lang ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                        Icon(
                            Icons.Default.CheckCircle, contentDescription = null,
                            tint = Color(0xFF3FB950), modifier = Modifier.size(18.dp).padding(top = 2.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(lang.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text(lang.runs, color = Color(0xFF8B949E), fontSize = 12.sp)
                            if (lang.editor != "—") {
                                Text("Editor: ${lang.editor}", color = Color(0xFF8B949E), fontSize = 12.sp)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    "The editor support (language servers) is set up automatically the first time you open one of these files. That step needs internet once.",
                    color = Color(0xFF8B949E), fontSize = 12.sp, lineHeight = 17.sp,
                )

                Spacer(Modifier.height(20.dp))
                HorizontalDivider(color = Color(0xFF30363D))
                Spacer(Modifier.height(16.dp))

                Text("Need another language?", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Python, C/C++, Rust, Go, PHP, Lua, Zig and more are one tap away in Marketplace → Runtimes. " +
                        "Each one installs with its language server, so autocomplete works too. " +
                        "New languages and updates show up there without updating the app.",
                    color = Color(0xFF8B949E), fontSize = 13.sp, lineHeight = 19.sp,
                )

                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = onOpenRuntimes,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F6FEB), contentColor = Color.White),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Icon(Icons.Default.Storefront, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Browse runtimes", fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = onFinished,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF58A6FF)),
                    border = BorderStroke(1.dp, Color(0xFF30363D)),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text("Start coding")
                }
            }
        }
    }
}
