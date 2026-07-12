package com.kodrix.zohaib.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kodrix.zohaib.viewmodel.TerminalViewModel

@Composable
fun PythonOnboardingScreen(
    viewModel: TerminalViewModel,
    onFinished: () -> Unit
) {
    var showLaterTutorial by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D1117)),
        contentAlignment = Alignment.Center
    ) {
        // Subtle background gradient glow
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF1F6FEB).copy(alpha = 0.15f),
                            Color.Transparent
                        ),
                        radius = 2000f
                    )
                )
        )

        AnimatedContent(
            targetState = showLaterTutorial,
            transitionSpec = {
                (slideInHorizontally { width -> width } + fadeIn()).togetherWith(
                    slideOutHorizontally { width -> -width } + fadeOut()
                )
            },
            label = "OnboardingTransition"
        ) { isTutorial ->
            if (isTutorial) {
                TutorialView(onFinished = onFinished)
            } else {
                MainQuestionView(
                    onYes = {
                        viewModel.installPythonInBackground()
                        android.widget.Toast.makeText(
                            viewModel.getApplication(),
                            "📥 Python 3 installation started in the background...",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                        onFinished()
                    },
                    onNo = {
                        showLaterTutorial = true
                    }
                )
            }
        }
    }
}

@Composable
private fun MainQuestionView(onYes: () -> Unit, onNo: () -> Unit) {
    Card(
        modifier = Modifier
            .width(500.dp)
            .padding(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF30363D)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Python Glow Icon
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(Color(0xFF3776AB), Color(0xFFFFD43B))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Terminal,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(40.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Add Python 3 to Kodrix?",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Enhance your editor with the full Python 3.13.13 runtime, allowing you to run Python scripts, manage libraries using pip, and execute binaries natively inside the built-in terminal.",
                color = Color(0xFF8B949E),
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = onYes,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF1F6FEB),
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Yes, Install Python 3", fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = onNo,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Color(0xFF58A6FF)
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF30363D)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("No, skip for now")
            }
        }
    }
}

@Composable
private fun TutorialView(onFinished: () -> Unit) {
    Card(
        modifier = Modifier
            .width(550.dp)
            .padding(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF30363D)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp)
        ) {
            Text(
                text = "How to Install Python 3 Later",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "You can install or update runtimes at any time with these simple steps:",
                color = Color(0xFF8B949E),
                fontSize = 14.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            TutorialStep(
                stepNumber = "1",
                icon = Icons.Default.Storefront,
                title = "Open the Marketplace",
                description = "Tap the storefront/marketplace icon on the left sidebar of your editor."
            )

            Spacer(modifier = Modifier.height(16.dp))

            TutorialStep(
                stepNumber = "2",
                icon = Icons.Default.SettingsEthernet,
                title = "Switch to Runtimes Tab",
                description = "Select the Runtimes tab to see available compilers, interpreters, and tools."
            )

            Spacer(modifier = Modifier.height(16.dp))

            TutorialStep(
                stepNumber = "3",
                icon = Icons.Default.Download,
                title = "Activate Python",
                description = "Locate Python Runtime and click Download. Once downloaded, tap Activate."
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = onFinished,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF238636),
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Okay, Got It!", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun TutorialStep(
    stepNumber: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size((28).dp)
                .clip(CircleShape)
                .background(Color(0xFF21262D))
                .border(1.dp, Color(0xFF30363D), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stepNumber,
                color = Color(0xFF58A6FF),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color(0xFF58A6FF),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                color = Color(0xFF8B949E),
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
        }
    }
}
