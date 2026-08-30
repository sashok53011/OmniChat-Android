package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chat_sessions")
data class ChatSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val activeProviderId: String = "gemini_flash",
    val systemPrompt: String? = null
)

@Entity(tableName = "chat_messages")
data class ChatMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val role: String, // "user" or "model" or "system" or "tool"
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val mediaUri: String? = null,
    val mediaType: String? = null, // "image", "video", "text"
    val isWebResult: Boolean = false
)

@Entity(tableName = "ai_providers")
data class AiProvider(
    @PrimaryKey val id: String,
    val name: String,
    val type: String, // "GEMINI", "OPENAI_COMPATIBLE"
    val baseUrl: String,
    val apiKey: String,
    val modelName: String,
    val isEnabled: Boolean = true,
    val priority: Int = 0
)

@Entity(tableName = "memory_items")
data class MemoryItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val content: String,
    val category: String = "general",
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "mcp_servers")
data class McpServer(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val endpointUrl: String,
    val isEnabled: Boolean = true
)

@Entity(tableName = "mcp_tools")
data class McpTool(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverId: Long,
    val name: String,
    val description: String,
    val inputSchemaJson: String = "{}",
    val isEnabled: Boolean = true
)

@Entity(tableName = "system_settings")
data class SystemSetting(
    @PrimaryKey val key: String,
    val value: String
)
