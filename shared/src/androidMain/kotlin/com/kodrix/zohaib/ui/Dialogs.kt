package com.kodrix.zohaib.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties

@Composable
fun NewItemDialog(title: String, initialValue: String = "", onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = Color.White, fontSize = 16.sp) },
        text = {
            BasicTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth().background(Color(0xFF0D1117), RoundedCornerShape(4.dp))
                    .border(1.dp, Color(0xFF30363D), RoundedCornerShape(4.dp)).padding(8.dp),
                textStyle = TextStyle(color = Color.White, fontSize = 14.sp, fontFamily = FontFamily.Monospace),
                cursorBrush = SolidColor(Color(0xFF58A6FF)),
                singleLine = true
            )
        },
        confirmButton = { TextButton(onClick = { if (name.isNotEmpty()) onConfirm(name) }) { Text("OK", color = Color(0xFF58A6FF)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Color.Gray) } },
        containerColor = Color(0xFF161B22)
    )
}

@Composable
fun CloneProjectDialog(onConfirm: (String, String, String, String) -> Unit, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clone Repository", color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogField("Repo URL (HTTPS)", url) { url = it }
                DialogField("Project Name", name) { name = it }
                DialogField("GitHub Username (Optional)", user) { user = it }
                DialogField("Personal Access Token (Optional)", token) { token = it }
                Text("Note: Use a token for private repos.", color = Color.DarkGray, fontSize = 10.sp)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(url, name, user, token) }) { Text("Clone", color = Color(0xFF58A6FF)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Color.Gray) } },
        containerColor = Color(0xFF161B22)
    )
}

@Composable
fun DialogField(label: String, value: String, onValueChange: (String) -> Unit) {
    Column {
        Text(label, color = Color.Gray, fontSize = 10.sp, modifier = Modifier.padding(bottom = 2.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().background(Color(0xFF0D1117), RoundedCornerShape(4.dp))
                .border(1.dp, Color(0xFF30363D), RoundedCornerShape(4.dp)).padding(6.dp),
            textStyle = TextStyle(color = Color.White, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
            cursorBrush = SolidColor(Color(0xFF58A6FF)),
            singleLine = true
        )
    }
}
