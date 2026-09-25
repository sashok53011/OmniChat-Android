package com.example.data.db

import androidx.room.*
import com.example.data.model.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AppDao {
    // --- Sessions ---
    @Query("SELECT * FROM chat_sessions ORDER BY createdAt DESC")
    fun getAllSessions(): Flow<List<ChatSession>>

    @Query("SELECT * FROM chat_sessions WHERE id = :id")
    suspend fun getSessionById(id: Long): ChatSession?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: ChatSession): Long

    @Update
    suspend fun updateSession(session: ChatSession)

    @Delete
    suspend fun deleteSession(session: ChatSession)

    // --- Messages ---
    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getMessagesForSession(sessionId: Long): Flow<List<ChatMessage>>

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getMessagesForSessionSync(sessionId: Long): List<ChatMessage>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessage): Long

    @Query("UPDATE chat_messages SET text = :text WHERE id = :messageId")
    suspend fun updateMessageText(messageId: Long, text: String)

    @Delete
    suspend fun deleteMessage(message: ChatMessage)

    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId")
    suspend fun deleteMessagesForSession(sessionId: Long)

    @Query("DELETE FROM chat_messages")
    suspend fun deleteAllMessages()

    @Query("DELETE FROM chat_sessions")
    suspend fun deleteAllSessions()

    // --- Providers ---
    @Query("SELECT * FROM ai_providers ORDER BY priority ASC")
    fun getAllProvidersFlow(): Flow<List<AiProvider>>

    @Query("SELECT * FROM ai_providers ORDER BY priority ASC")
    suspend fun getAllProviders(): List<AiProvider>

    @Query("SELECT * FROM ai_providers WHERE id = :id")
    suspend fun getProviderById(id: String): AiProvider?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProvider(provider: AiProvider)

    @Delete
    suspend fun deleteProvider(provider: AiProvider)

    // --- Memory ---
    @Query("SELECT * FROM memory_items ORDER BY timestamp DESC")
    fun getAllMemoryItems(): Flow<List<MemoryItem>>

    @Query("SELECT * FROM memory_items ORDER BY timestamp DESC")
    suspend fun getAllMemoryItemsSync(): List<MemoryItem>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemoryItem(item: MemoryItem)

    @Delete
    suspend fun deleteMemoryItem(item: MemoryItem)

    // --- MCP Servers ---
    @Query("SELECT * FROM mcp_servers")
    fun getAllMcpServersFlow(): Flow<List<McpServer>>

    @Query("SELECT * FROM mcp_servers")
    suspend fun getAllMcpServers(): List<McpServer>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMcpServer(server: McpServer): Long

    @Delete
    suspend fun deleteMcpServer(server: McpServer)

    // --- MCP Tools ---
    @Query("SELECT * FROM mcp_tools")
    fun getAllMcpToolsFlow(): Flow<List<McpTool>>

    @Query("SELECT * FROM mcp_tools WHERE serverId = :serverId")
    suspend fun getMcpToolsByServer(serverId: Long): List<McpTool>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMcpTool(tool: McpTool)

    @Query("DELETE FROM mcp_tools WHERE serverId = :serverId")
    suspend fun deleteMcpToolsForServer(serverId: Long)

    @Query("UPDATE mcp_tools SET isEnabled = :isEnabled WHERE id = :toolId")
    suspend fun updateMcpToolEnabled(toolId: Long, isEnabled: Boolean)

    // --- Settings ---
    @Query("SELECT * FROM system_settings WHERE `key` = :key")
    suspend fun getSetting(key: String): SystemSetting?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSetting(setting: SystemSetting)

    // --- Export/Import helpers ---
    @Query("SELECT * FROM system_settings")
    suspend fun getAllSettingsSync(): List<SystemSetting>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllSettings(settings: List<SystemSetting>)

    @Query("DELETE FROM system_settings")
    suspend fun deleteAllSettingsSync()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllProviders(providers: List<AiProvider>)

    @Query("DELETE FROM ai_providers")
    suspend fun deleteAllProvidersSync()

    @Query("DELETE FROM ai_providers WHERE id != 'opencodego'")
    suspend fun deleteAllProvidersExceptOpencode()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllMemoryItems(items: List<MemoryItem>)

    @Query("DELETE FROM memory_items")
    suspend fun deleteAllMemoryItemsSync()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllMcpServers(servers: List<McpServer>)

    @Query("DELETE FROM mcp_servers")
    suspend fun deleteAllMcpServersSync()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllMcpTools(tools: List<McpTool>)

    @Query("DELETE FROM mcp_tools")
    suspend fun deleteAllMcpToolsSync()
}
