package com.kodrix.zohaib

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kodrix.zohaib.analytics.AnalyticsHelper
import com.kodrix.zohaib.ui.IDEView
import com.kodrix.zohaib.ui.SplashScreen
import com.kodrix.zohaib.ui.PythonOnboardingScreen
import com.kodrix.zohaib.viewmodel.TerminalViewModel

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: TerminalViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Log device specs on first launch
        AnalyticsHelper.logDeviceSpecs(this)
        
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        viewModel = androidx.lifecycle.ViewModelProvider(this)[TerminalViewModel::class.java]
        setContent {
            KodrixTheme {
                val isReady by viewModel.isReady.collectAsState()
                val ghDeviceCode by viewModel.ghDeviceCode.collectAsState()
                
                // Automatically copy gh device code & open browser when detected
                androidx.compose.runtime.LaunchedEffect(ghDeviceCode) {
                    ghDeviceCode?.let { code ->
                        android.widget.Toast.makeText(
                            this@MainActivity,
                            "GitHub Code Copied: $code. Opening browser...",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                        viewModel.handleGhDeviceCode(code)
                    }
                }
                
                // Automatically ask for permissions on startup
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    val permissions = mutableListOf<String>()
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        permissions.add(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                    permissions.add(android.Manifest.permission.CAMERA)
                    permissions.add(android.Manifest.permission.RECORD_AUDIO)

                    val toRequest = permissions.filter {
                        androidx.core.content.ContextCompat.checkSelfPermission(this@MainActivity, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
                    }

                    if (toRequest.isNotEmpty()) {
                        androidx.core.app.ActivityCompat.requestPermissions(this@MainActivity, toRequest.toTypedArray(), 101)
                    }
                }

                if (isReady) {
                    val showOnboarding by viewModel.showPythonOnboarding.collectAsState()
                    if (showOnboarding) {
                        PythonOnboardingScreen(
                            viewModel = viewModel,
                            onFinished = { viewModel.dismissPythonOnboarding() }
                        )
                    } else {
                        IDEView(viewModel)
                    }
                } else {
                    SplashScreen(viewModel)
                }
            }
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: android.content.Intent?) {
        intent?.data?.let { uri ->
            if (uri.scheme == "kodrix" && uri.host == "github-auth") {
                val code = uri.getQueryParameter("code")
                val state = uri.getQueryParameter("state")
                if (code != null && state != null && ::viewModel.isInitialized) {
                    if (viewModel.verifyAuthState(state)) {
                        android.widget.Toast.makeText(this, "Auth code captured!", android.widget.Toast.LENGTH_SHORT).show()
                        viewModel.handleGithubCallback(code)
                    } else {
                        android.widget.Toast.makeText(this, "Security Error: Invalid OAuth State", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
}

@Composable
fun KodrixTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF58A6FF),
            background = Color(0xFF0D1117),
            surface = Color(0xFF161B22)
        ),
        content = content
    )
}
