package com.example.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Card
import androidx.wear.compose.material.CompactButton
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.Vignette
import androidx.wear.compose.material.VignettePosition

data class WearMessage(
    val type: String,
    val content: String,
    val timestamp: String,
    val sender: String
)

@Composable
fun WearOmniChatScreen(
    messages: List<WearMessage>,
    isConnected: Boolean,
    statusMessage: String = "",
    errorMessage: String? = null,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onClearMessages: () -> Unit,
    onDismissError: () -> Unit = {}
) {
    val scalingLazyListState = androidx.wear.compose.foundation.lazy.rememberScalingLazyListState()

    Scaffold(
        timeText = { TimeText() },
        vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) }
    ) {
        ScalingLazyColumn(
            state = scalingLazyListState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Header
            item {
                Text(
                    text = "OmniChat",
                    style = MaterialTheme.typography.title3,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colors.primary
                )
            }

            // Status
            item {
                Text(
                    text = statusMessage,
                    fontSize = 10.sp,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                )
            }

            // Error message (if any)
            if (errorMessage != null) {
                item {
                    Card(
                        onClick = onDismissError,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "\u26A0 $errorMessage",
                            fontSize = 10.sp,
                            modifier = Modifier.padding(8.dp),
                            color = MaterialTheme.colors.error
                        )
                    }
                }
            }

            // Connection status
            item {
                Card(
                    onClick = { if (isConnected) onDisconnect() else onConnect() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (isConnected) "\u26A1" else "\u274C",
                            fontSize = 16.sp
                        )
                        Text(
                            text = if (isConnected) "Connected" else "Disconnected",
                            fontSize = 11.sp,
                            color = MaterialTheme.colors.onSurface
                        )
                    }
                }
            }

            // Clear button
            item {
                CompactButton(
                    onClick = onClearMessages,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("\uD83D\uDD04 Clear", fontSize = 11.sp)
                }
            }

            // Messages header
            item {
                Text(
                    text = "Messages (${messages.size})",
                    style = MaterialTheme.typography.caption3,
                    color = MaterialTheme.colors.primary,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Messages
            if (messages.isEmpty()) {
                item {
                    Text(
                        text = "No messages yet",
                        style = MaterialTheme.typography.body2,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f)
                    )
                }
            } else {
                items(messages) { message ->
                    Card(
                        onClick = { },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = message.sender,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colors.primary
                                )
                                Text(
                                    text = message.timestamp,
                                    fontSize = 8.sp,
                                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f)
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            MarkdownText(
                                text = message.content,
                                baseFontSize = 11.sp,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    }
}
