package com.example.wear.ui.theme

import androidx.compose.runtime.Composable
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme

@Composable
fun WearOmniChatTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors = Colors(
            primary = androidx.compose.ui.graphics.Color(0xFF1565C0),
            primaryVariant = androidx.compose.ui.graphics.Color(0xFF0D47A1),
            secondary = androidx.compose.ui.graphics.Color(0xFF4CAF50),
            secondaryVariant = androidx.compose.ui.graphics.Color(0xFF2E7D32),
            background = androidx.compose.ui.graphics.Color(0xFF1A1A1A),
            surface = androidx.compose.ui.graphics.Color(0xFF2D2D2D),
            error = androidx.compose.ui.graphics.Color(0xFFEF5350),
            onPrimary = androidx.compose.ui.graphics.Color.White,
            onSecondary = androidx.compose.ui.graphics.Color.White,
            onBackground = androidx.compose.ui.graphics.Color.White,
            onSurface = androidx.compose.ui.graphics.Color.White,
            onError = androidx.compose.ui.graphics.Color.White
        ),
        content = content
    )
}
