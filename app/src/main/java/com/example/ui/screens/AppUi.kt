package com.example.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.compose.ui.text.TextStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.data.model.*
import com.example.bluetooth.BtDeviceConfig
import com.example.ui.theme.Localization
import com.example.ui.viewmodel.MainViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppUi(viewModel: MainViewModel) {
    val context = LocalContext.current
    val currentTab = remember { mutableStateOf("chat") }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val lang by viewModel.appLanguage.collectAsStateWithLifecycle()
    val chatSessions by viewModel.chatSessions.collectAsStateWithLifecycle()
    val currentSessionId by viewModel.currentSessionId.collectAsStateWithLifecycle()

    var searchQuery by remember { mutableStateOf("") }
    val filteredSessions = remember(chatSessions, searchQuery) {
        if (searchQuery.isBlank()) chatSessions
        else chatSessions.filter { it.title.contains(searchQuery, ignoreCase = true) }
    }

    var showClearConfirm by remember { mutableStateOf(false) }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(Localization.getString("clear_history", lang)) },
            text = { Text(Localization.getString("clear_history_confirm", lang)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearAllSessions()
                        showClearConfirm = false
                    }
                ) {
                    Text(Localization.getString("delete", lang), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(Localization.getString("cancel", lang))
                }
            }
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.background,
                drawerTonalElevation = 4.dp,
                modifier = Modifier.width(320.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = Localization.getString("new_chat", lang),
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        IconButton(onClick = { scope.launch { drawerState.close() } }) {
                            Icon(Icons.Filled.Close, contentDescription = "Close")
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = {
                            viewModel.startNewChatSession()
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .testTag("create_chat_button"),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Add")
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(Localization.getString("new_chat", lang), fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Search Field
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text(Localization.getString("search_chat", lang), fontSize = 14.sp) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Filled.Clear, contentDescription = null, modifier = Modifier.size(18.dp))
                                }
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                        )
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                    Spacer(modifier = Modifier.height(8.dp))

                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(filteredSessions) { session ->
                            val isSelected = session.id == currentSessionId
                            
                            val sessionIcon = remember(session.title) {
                                val title = session.title.lowercase()
                                when {
                                    title.contains("code") || title.contains("прогр") || title.contains("kotlin") || title.contains("java") || title.contains("script") -> Icons.Filled.Terminal
                                    title.contains("image") || title.contains("фото") || title.contains("picture") || title.contains("рисун") -> Icons.Filled.Brush
                                    title.contains("math") || title.contains("мат") || title.contains("calc") || title.contains("числ") -> Icons.Filled.Functions
                                    title.contains("travel") || title.contains("путеш") || title.contains("trip") || title.contains("отпуск") -> Icons.Filled.Flight
                                    title.contains("food") || title.contains("ед") || title.contains("cook") || title.contains("рецепт") -> Icons.Filled.Restaurant
                                    title.contains("music") || title.contains("муз") || title.contains("песн") || title.contains("song") -> Icons.Filled.MusicNote
                                    title.contains("sport") || title.contains("спорт") || title.contains("fit") || title.contains("тренир") -> Icons.Filled.FitnessCenter
                                    title.contains("news") || title.contains("новост") || title.contains("world") || title.contains("мир") -> Icons.Filled.Public
                                    title.contains("book") || title.contains("книг") || title.contains("read") || title.contains("чит") -> Icons.Filled.MenuBook
                                    title.contains("movie") || title.contains("фильм") || title.contains("cinema") || title.contains("кино") -> Icons.Filled.Movie
                                    title.contains("game") || title.contains("игр") || title.contains("play") -> Icons.Filled.SportsEsports
                                    title.contains("money") || title.contains("деньг") || title.contains("finance") || title.contains("фин") -> Icons.Filled.Payments
                                    else -> if (isSelected) Icons.Filled.ChatBubble else Icons.Outlined.ChatBubbleOutline
                                }
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                                        else Color.Transparent
                                    )
                                    .clickable {
                                        viewModel.selectSession(session.id)
                                        scope.launch { drawerState.close() }
                                    }
                                    .padding(horizontal = 12.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = sessionIcon,
                                    contentDescription = "Chat Icon",
                                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = session.title,
                                        fontSize = 14.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onBackground,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = Localization.getRelativeTimeString(session.createdAt, lang),
                                        fontSize = 11.sp,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f) 
                                                else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                                        maxLines = 1
                                    )
                                }
                                IconButton(
                                    onClick = { viewModel.deleteSession(session) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = "Delete",
                                        tint = if (isSelected) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                    Spacer(modifier = Modifier.height(8.dp))

                    TextButton(
                        onClick = { showClearConfirm = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(Localization.getString("clear_history", lang), fontWeight = FontWeight.SemiBold)
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                    Spacer(modifier = Modifier.height(8.dp))

                    // Navigation items
                    val navItems = listOf(
                        Triple("chat", Icons.Filled.ChatBubble, "chat"),
                        Triple("providers", Icons.Filled.Hub, "providers"),
                        Triple("memory", Icons.Filled.Psychology, "memory"),
                        Triple("mcp", Icons.Filled.Build, "mcp")
                    )
                    navItems.forEach { (tabId, icon, labelKey) ->
                        val isSelected = currentTab.value == tabId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                    else Color.Transparent
                                )
                                .clickable {
                                    currentTab.value = tabId
                                    scope.launch { drawerState.close() }
                                }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = tabId,
                                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = Localization.getString(labelKey, lang),
                                fontSize = 14.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                    Spacer(modifier = Modifier.height(8.dp))

                    // Settings option
                    val isSettingsSelected = currentTab.value == "settings"
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isSettingsSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                else Color.Transparent
                            )
                            .clickable {
                                currentTab.value = "settings"
                                scope.launch { drawerState.close() }
                            }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "settings",
                            tint = if (isSettingsSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = Localization.getString("settings", lang),
                            fontSize = 14.sp,
                            fontWeight = if (isSettingsSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSettingsSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
            }
        }
    ) {
        Scaffold { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                MaterialTheme.colorScheme.background,
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                            )
                        )
                    )
            ) {
                when (currentTab.value) {
                    "chat" -> ChatTabScreen(viewModel, lang, drawerState, currentTab)
                    "providers" -> ProvidersTabScreen(viewModel, lang)
                    "memory" -> MemoryTabScreen(viewModel, lang)
                    "mcp" -> McpTabScreen(viewModel, lang)
                    "settings" -> SettingsTabScreen(viewModel, lang)
                }
            }
        }
    }
}

// ==================== CHAT TAB ====================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatTabScreen(viewModel: MainViewModel, lang: String, drawerState: DrawerState, currentTab: MutableState<String>) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val messages by viewModel.currentMessages.collectAsStateWithLifecycle()
    val isGenerating by viewModel.isGenerating.collectAsStateWithLifecycle()
    val statusText by viewModel.statusText.collectAsStateWithLifecycle()
    val streamingText by viewModel.streamingText.collectAsStateWithLifecycle()
    val isStreamingActive by viewModel.isStreamingActive.collectAsStateWithLifecycle()
    val isListening by viewModel.isListening.collectAsStateWithLifecycle()
    val continueListeningMode by viewModel.continueListeningMode.collectAsStateWithLifecycle()
    val voiceMessageQueue by viewModel.voiceMessageQueue.collectAsStateWithLifecycle()
    val attachments by viewModel.attachments.collectAsStateWithLifecycle()
    val providers by viewModel.aiProviders.collectAsStateWithLifecycle()
    val chatSessions by viewModel.chatSessions.collectAsStateWithLifecycle()
    val currentSessionId by viewModel.currentSessionId.collectAsStateWithLifecycle()
    val webSearchEnabled by viewModel.webSearchEnabled.collectAsStateWithLifecycle()
    val customSuggestions by viewModel.customSuggestions.collectAsStateWithLifecycle()
    val freeRamGb by viewModel.freeRamGb.collectAsStateWithLifecycle()
    val totalRamGb by viewModel.totalRamGb.collectAsStateWithLifecycle()
    val usedRamGb by viewModel.usedRamGb.collectAsStateWithLifecycle()
    val ramProgress by viewModel.ramProgress.collectAsStateWithLifecycle()
    val appMemoryUsedMb by viewModel.appMemoryUsedMb.collectAsStateWithLifecycle()
    val appMemoryMaxMb by viewModel.appMemoryMaxMb.collectAsStateWithLifecycle()
    val isGgufLoaded by viewModel.isGgufLoaded.collectAsStateWithLifecycle()
    val ggufLoadedModelName by viewModel.ggufLoadedModelName.collectAsStateWithLifecycle()
    val ggufModelRamSizeGb by viewModel.ggufModelRamSizeGb.collectAsStateWithLifecycle()

    var showPersonaSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()

    var exportContent by remember { mutableStateOf("") }
    var exportMimeType by remember { mutableStateOf("text/plain") }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(exportMimeType)
    ) { uri ->
        uri?.let {
            context.contentResolver.openOutputStream(it)?.use { outputStream ->
                outputStream.write(exportContent.toByteArray())
            }
        }
    }

    fun exportAsJson(session: ChatSession) {
        val json = buildString {
            append("{\n")
            append("  \"title\": \"${session.title}\",\n")
            append("  \"messages\": [\n")
            messages.forEachIndexed { index, msg ->
                append("    {\n")
                append("      \"role\": \"${msg.role}\",\n")
                append("      \"content\": \"${msg.text.replace("\"", "\\\"").replace("\n", "\\n")}\"\n")
                append("    }${if (index < messages.size - 1) "," else ""}\n")
            }
            append("  ]\n")
            append("}")
        }
        exportContent = json
        exportMimeType = "application/json"
        val prefix = Localization.getString("export_filename_prefix", lang)
        createDocumentLauncher.launch("${prefix}_${session.id}.json")
    }

    fun exportAsMarkdown(session: ChatSession) {
        val md = buildString {
            append("# ${session.title}\n\n")
            messages.forEach { msg ->
                val roleName = if (msg.role == "user") "User" else "AI"
                append("**$roleName**: ${msg.text}\n\n")
            }
        }
        exportContent = md
        exportMimeType = "text/markdown"
        val prefix = Localization.getString("export_filename_prefix", lang)
        createDocumentLauncher.launch("${prefix}_${session.id}.md")
    }

    val activeSession = chatSessions.find { it.id == currentSessionId }
    val activeProvider = providers.find { it.id == (activeSession?.activeProviderId ?: "gemini_flash") } ?: providers.firstOrNull()

    var textInput by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Scroll to bottom on new message or streaming text update
    LaunchedEffect(messages.size, streamingText) {
        if (listState.layoutInfo.totalItemsCount > 0) {
            listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
        }
    }

    // Media button scroll (vol+/- jump)
    val scrollJump by viewModel.scrollJump.collectAsStateWithLifecycle()
    LaunchedEffect(scrollJump) {
        if (scrollJump != 0 && listState.layoutInfo.totalItemsCount > 0) {
            val target = (listState.firstVisibleItemIndex + scrollJump * 2).coerceIn(0, listState.layoutInfo.totalItemsCount - 1)
            listState.animateScrollToItem(target)
        }
    }

    // Media button continuous scroll (play/pause)
    val scrollDirection by viewModel.scrollDirection.collectAsStateWithLifecycle()
    LaunchedEffect(scrollDirection) {
        if (scrollDirection != 0) {
            while (scrollDirection != 0) {
                val target = (listState.firstVisibleItemIndex + scrollDirection).coerceIn(0, listState.layoutInfo.totalItemsCount - 1)
                listState.animateScrollToItem(target)
                kotlinx.coroutines.delay(150)
            }
        }
    }

    // Clipboard image capture
    val processClipboard = {
        val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        val clipData = clip?.primaryClip
        if (clipData != null && clipData.itemCount > 0) {
            val item = clipData.getItemAt(0)
            val uri = item.uri
            if (uri != null) {
                viewModel.addAttachment(uri)
                Toast.makeText(context, Localization.getString("image_attached", lang), Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Photo picker launcher
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { viewModel.addAttachment(it) }
    }

    // Video picker launcher
    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { viewModel.addAttachment(it) }
    }

    // File picker launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.addAttachment(it) }
    }

    // Audio picker launcher
    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.addAttachment(it) }
    }

    // Camera launcher
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && cameraUri != null) {
            viewModel.addAttachment(cameraUri!!)
        }
    }

    // Voice permission launcher
    val voicePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.startVoiceListening()
        } else {
            Toast.makeText(context,
                if (lang == "ru") "Разрешение на микрофон необходимо для голосового ввода"
                else "Microphone permission is required for voice input",
                Toast.LENGTH_LONG).show()
        }
    }

    // Observe speech text and update textInput
    val speechText by viewModel.speechText.collectAsStateWithLifecycle()
    LaunchedEffect(speechText) {
        if (speechText.isNotBlank()) {
            textInput = speechText
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Chat Toolbar
        TopAppBar(
            title = {
                Column {
                    Text(
                        text = activeSession?.title ?: Localization.getString("app_title", lang),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${Localization.getString("active_provider", lang)}: ${activeProvider?.name ?: "Gemini"}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (activeSession?.systemPrompt?.isNotBlank() == true) {
                            val persona = viewModel.personas.find { it.prompt == activeSession.systemPrompt }
                            if (persona != null) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = Localization.getString(persona.nameKey, lang),
                                        fontSize = 9.sp,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                            }
                        }
                    }
                }
            },
            navigationIcon = {
                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                    Icon(Icons.Filled.Menu, contentDescription = "Menu")
                }
            },
            actions = {
                // Clipboard paste button
                IconButton(onClick = processClipboard) {
                    Icon(Icons.Filled.ContentPaste, contentDescription = "Paste", tint = MaterialTheme.colorScheme.primary)
                }

                // Dynamic selector of AI Providers
                var showProviderMenu by remember { mutableStateOf(false) }
                IconButton(onClick = { showProviderMenu = true }) {
                    Icon(Icons.Filled.Hub, contentDescription = "Switch AI", tint = MaterialTheme.colorScheme.secondary)
                }
                DropdownMenu(
                    expanded = showProviderMenu,
                    onDismissRequest = { showProviderMenu = false },
                    modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    providers.filter { it.isEnabled }.forEach { provider ->
                        DropdownMenuItem(
                            text = { Text(provider.name, color = MaterialTheme.colorScheme.onBackground) },
                            onClick = {
                                showProviderMenu = false
                                if (activeSession != null) {
                                    scope.launch {
                                        viewModel.updateSession(activeSession.copy(activeProviderId = provider.id))
                                    }
                                }
                            }
                        )
                    }
                }

                // Web Search toggle
                IconToggleButton(
                    checked = webSearchEnabled,
                    onCheckedChange = { viewModel.toggleWebSearch(it) }
                ) {
                    Icon(
                        Icons.Filled.Language,
                        contentDescription = "Web Search",
                        tint = if (webSearchEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                    )
                }

                // Export Chat dropdown
                var showExportMenu by remember { mutableStateOf(false) }
                IconButton(onClick = { showExportMenu = true }) {
                    Icon(Icons.Filled.Share, contentDescription = "Export Chat", tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f))
                }
                
                // Settings button
                IconButton(onClick = {
                    currentTab.value = "settings"
                }) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = "Settings",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                // Persona / Role button
                IconButton(onClick = { showPersonaSheet = true }) {
                    Icon(Icons.Filled.Face, contentDescription = "Personas", tint = MaterialTheme.colorScheme.primary)
                }

                DropdownMenu(
                    expanded = showExportMenu,
                    onDismissRequest = { showExportMenu = false },
                    modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Code, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(Localization.getString("export_json", lang), fontSize = 14.sp)
                            }
                        },
                        onClick = {
                            showExportMenu = false
                            activeSession?.let { exportAsJson(it) }
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Description, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.secondary)
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(Localization.getString("export_markdown", lang), fontSize = 14.sp)
                            }
                        },
                        onClick = {
                            showExportMenu = false
                            activeSession?.let { exportAsMarkdown(it) }
                        }
                    )
                    Divider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Public, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.tertiary)
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(Localization.getString("wp_settings_title", lang), fontSize = 14.sp)
                            }
                        },
                        onClick = {
                            showExportMenu = false
                            viewModel.postCurrentChatToWordPress { success ->
                                Toast.makeText(
                                    context,
                                    if (success) Localization.getString("wp_post_success", lang)
                                    else Localization.getString("wp_post_error", lang),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        )

        // Polished system status badges row
        if (showPersonaSheet) {
            ModalBottomSheet(
                onDismissRequest = { showPersonaSheet = false },
                sheetState = sheetState,
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .padding(bottom = 32.dp)
                ) {
                    Text(
                        text = Localization.getString("personas_title", lang),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    LazyColumn {
                        items(viewModel.personas) { persona ->
                            Card(
                                onClick = {
                                    viewModel.setPersona(persona)
                                    scope.launch { sheetState.hide() }.invokeOnCompletion {
                                        showPersonaSheet = false
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (activeSession?.systemPrompt == persona.prompt)
                                        MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .padding(16.dp)
                                        .fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val icon = when(persona.id) {
                                        "coder" -> Icons.Filled.Terminal
                                        "writer" -> Icons.Filled.Edit
                                        "researcher" -> Icons.Filled.Search
                                        "tutor" -> Icons.Filled.School
                                        else -> Icons.Filled.Face
                                    }
                                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Column {
                                        Text(
                                            text = Localization.getString(persona.nameKey, lang),
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = Localization.getString(persona.descKey, lang),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        
        // Chat Input Panel (TOP — above messages)
        var showAttachMenu by remember { mutableStateOf(false) }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                // Inline row: [+] [TextInput] [Send]
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom
                ) {
                    // "+" button to expand attachment tools
                    IconButton(
                        onClick = { showAttachMenu = !showAttachMenu },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = if (showAttachMenu) Icons.Filled.Close else Icons.Filled.Add,
                            contentDescription = if (showAttachMenu) "Close" else "Attach",
                            tint = if (showAttachMenu) MaterialTheme.colorScheme.error
                                   else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    // Text input — inline, takes remaining space
                    OutlinedTextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 40.dp, max = 120.dp),
                        placeholder = {
                            Text(
                                Localization.getString("send_hint", lang),
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        },
                        maxLines = 4,
                        textStyle = TextStyle(fontSize = 15.sp),
                        shape = RoundedCornerShape(20.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        )
                    )

                    // Send / Stop button — inline, circular, inside the row
                    IconButton(
                        onClick = {
                            if (isGenerating) {
                                viewModel.stopGeneration()
                            } else if (textInput.isNotBlank()) {
                                viewModel.sendMessage(textInput)
                                textInput = ""
                            }
                        },
                        enabled = isGenerating || textInput.isNotBlank(),
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(
                                if (isGenerating)
                                    MaterialTheme.colorScheme.error
                                else if (textInput.isNotBlank())
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                            )
                    ) {
                        Icon(
                            if (isGenerating) Icons.Filled.Close else Icons.Filled.Send,
                            contentDescription = if (isGenerating) "Stop" else "Send",
                            tint = if (isGenerating || textInput.isNotBlank())
                                MaterialTheme.colorScheme.onPrimary
                            else
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // Expandable attachment tools (fly out when "+" tapped)
                if (showAttachMenu) {
                    AnimatedVisibility(visible = showAttachMenu) {
                        Column(modifier = Modifier.padding(top = 4.dp)) {
                            // Tool buttons row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // File
                                ToolButton(
                                    icon = Icons.Filled.AttachFile,
                                    label = if (lang == "ru") "Файл" else "File",
                                    onClick = {
                                        showAttachMenu = false
                                        filePickerLauncher.launch(arrayOf("*/*"))
                                    }
                                )
                                // Audio
                                ToolButton(
                                    icon = Icons.Filled.AudioFile,
                                    label = if (lang == "ru") "Аудио" else "Audio",
                                    onClick = {
                                        showAttachMenu = false
                                        audioPickerLauncher.launch(arrayOf("audio/*"))
                                    }
                                )
                                // Camera / Gallery
                                var showCameraSheet by remember { mutableStateOf(false) }
                                ToolButton(
                                    icon = Icons.Filled.CameraAlt,
                                    label = if (lang == "ru") "Фото" else "Photo",
                                    onClick = { showCameraSheet = true }
                                )
                                if (showCameraSheet) {
                                    ModalBottomSheet(
                                        onDismissRequest = { showCameraSheet = false },
                                        containerColor = MaterialTheme.colorScheme.surface
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(16.dp)
                                                .padding(bottom = 32.dp)
                                        ) {
                                            Text(
                                                text = if (lang == "ru") "Добавить фото" else "Add Photo",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 16.sp,
                                                modifier = Modifier.padding(bottom = 12.dp)
                                            )
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                                            ) {
                                                Card(
                                                    onClick = {
                                                        showCameraSheet = false
                                                        showAttachMenu = false
                                                        val photoFile = java.io.File(
                                                            context.cacheDir,
                                                            "camera_${System.currentTimeMillis()}.jpg"
                                                        )
                                                        cameraUri = androidx.core.content.FileProvider.getUriForFile(
                                                            context,
                                                            "${context.packageName}.fileprovider",
                                                            photoFile
                                                        )
                                                        cameraLauncher.launch(cameraUri!!)
                                                    },
                                                    modifier = Modifier.weight(1f),
                                                    colors = CardDefaults.cardColors(
                                                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                                                    )
                                                ) {
                                                    Column(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(16.dp),
                                                        horizontalAlignment = Alignment.CenterHorizontally
                                                    ) {
                                                        Icon(
                                                            Icons.Filled.CameraAlt,
                                                            contentDescription = null,
                                                            tint = MaterialTheme.colorScheme.primary,
                                                            modifier = Modifier.size(32.dp)
                                                        )
                                                        Spacer(modifier = Modifier.height(8.dp))
                                                        Text(
                                                            if (lang == "ru") "Камера" else "Camera",
                                                            fontSize = 13.sp,
                                                            fontWeight = FontWeight.Medium
                                                        )
                                                    }
                                                }
                                                Card(
                                                    onClick = {
                                                        showCameraSheet = false
                                                        showAttachMenu = false
                                                        photoPickerLauncher.launch(
                                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                                        )
                                                    },
                                                    modifier = Modifier.weight(1f),
                                                    colors = CardDefaults.cardColors(
                                                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                                                    )
                                                ) {
                                                    Column(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(16.dp),
                                                        horizontalAlignment = Alignment.CenterHorizontally
                                                    ) {
                                                        Icon(
                                                            Icons.Filled.PhotoLibrary,
                                                            contentDescription = null,
                                                            tint = MaterialTheme.colorScheme.secondary,
                                                            modifier = Modifier.size(32.dp)
                                                        )
                                                        Spacer(modifier = Modifier.height(8.dp))
                                                        Text(
                                                            if (lang == "ru") "Галерея" else "Gallery",
                                                            fontSize = 13.sp,
                                                            fontWeight = FontWeight.Medium
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                // Video
                                ToolButton(
                                    icon = Icons.Filled.Videocam,
                                    label = if (lang == "ru") "Видео" else "Video",
                                    onClick = {
                                        showAttachMenu = false
                                        videoPickerLauncher.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                                        )
                                    }
                                )
                                // Voice input
                                ToolButton(
                                    icon = if (isListening) Icons.Filled.MicOff else Icons.Filled.Mic,
                                    label = if (isListening) (if (lang == "ru") "Стоп" else "Stop")
                                            else (if (lang == "ru") "Голос" else "Voice"),
                                    tint = if (isListening) MaterialTheme.colorScheme.error else null,
                                    onClick = {
                                        if (isListening) {
                                            viewModel.stopVoiceListening()
                                        } else {
                                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                                                == PackageManager.PERMISSION_GRANTED) {
                                                viewModel.startVoiceListening()
                                            } else {
                                                voicePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                            }
                                        }
                                    }
                                )
                                // Continue Listening toggle
                                ToolButton(
                                    icon = if (continueListeningMode) Icons.Filled.Hearing else Icons.Filled.HearingDisabled,
                                    label = if (continueListeningMode) (if (lang == "ru") "Выкл" else "Off")
                                            else (if (lang == "ru") "Авто" else "Auto"),
                                    tint = if (continueListeningMode) MaterialTheme.colorScheme.primary else null,
                                    onClick = { viewModel.toggleContinueListening() }
                                )
                            }
                        }
                    }
                }

                // Voice recording indicator
                if (isListening || voiceMessageQueue.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isListening) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(if (continueListeningMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text(
                            text = buildString {
                                if (isListening) {
                                    append(if (continueListeningMode) "🎙 Hands-free: listening..." else Localization.getString("recording", lang))
                                }
                                if (voiceMessageQueue.isNotEmpty()) {
                                    if (isListening) append("  •  ")
                                    append("📥 Queue: ${voiceMessageQueue.size}")
                                }
                            },
                            fontSize = 11.sp,
                            color = when {
                                voiceMessageQueue.isNotEmpty() -> MaterialTheme.colorScheme.tertiary
                                continueListeningMode -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.error
                            },
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
        // Active attachments preview list
        if (attachments.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                    .padding(8.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                attachments.forEach { uri ->
                    Box(
                        modifier = Modifier
                            .size(60.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        val mimeType = context.contentResolver.getType(uri) ?: ""
                        if (mimeType.startsWith("image/")) {
                            AsyncImage(
                                model = uri,
                                contentDescription = "thumb",
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = when {
                                        mimeType.startsWith("video/") -> Icons.Filled.VideoFile
                                        mimeType.startsWith("audio/") -> Icons.Filled.AudioFile
                                        else -> Icons.Filled.InsertDriveFile
                                    },
                                    contentDescription = "file",
                                    tint = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }
                        IconButton(
                            onClick = { viewModel.removeAttachment(uri) },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(20.dp)
                                .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "close",
                                tint = Color.White,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }
                }
            }
        }

        // Dynamic Memory Resource Bar
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 12.dp)
        ) {
            items(messages) { message ->
                MessageBubble(message = message, viewModel = viewModel, lang = lang)
            }

            // Streaming message in progress (appears before typing indicator)
            if (isStreamingActive && streamingText.isNotEmpty()) {
                item {
                    MessageBubble(
                        message = ChatMessage(
                            sessionId = 0,
                            role = "model",
                            text = streamingText
                        ),
                        viewModel = viewModel,
                        lang = lang
                    )
                }
            }

            // Typing Indicator
            if (isGenerating) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = statusText.ifBlank { Localization.getString("status_thinking", lang) },
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }


        // Suggestions disabled

        // Audio listing overlay indicator
        if (isListening) {
            PulsingAudioIndicator(lang)
        }

        // Dynamic Memory Resource Bar
        LaunchedEffect(Unit) {
            while (true) {
                viewModel.updateRamStats()
                kotlinx.coroutines.delay(3000)
            }
        }
    }
}

// ==================== MESSAGE CARD ====================
@Composable
fun MessageBubble(message: ChatMessage, viewModel: MainViewModel, lang: String) {
    val isUser = message.role == "user"
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val currentlySpeakingText by viewModel.currentlySpeakingText.collectAsStateWithLifecycle()
    val isSpeakingThis = currentlySpeakingText == message.text

    val alignment = if (isUser) Alignment.End else Alignment.Start
    val containerColor = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f)
    }
    val contentColor = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant

    val shape = if (isUser) {
        RoundedCornerShape(16.dp, 16.dp, 2.dp, 16.dp)
    } else {
        RoundedCornerShape(16.dp, 16.dp, 16.dp, 2.dp)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalAlignment = alignment
    ) {
        Card(
            shape = shape,
            colors = CardDefaults.cardColors(
                containerColor = containerColor,
                contentColor = contentColor
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            modifier = Modifier.fillMaxWidth(0.85f)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                // Attachments preview inside message bubble
                if (message.mediaUri != null) {
                    val mediaUri = Uri.parse(message.mediaUri)
                    if (message.mediaType == "image") {
                        AsyncImage(
                            model = mediaUri,
                            contentDescription = "attachment",
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 180.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .padding(bottom = 8.dp)
                        )
                    } else if (message.mediaType == "video") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.Black)
                                .padding(bottom = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.VideoLibrary, contentDescription = "Video file", tint = Color.White)
                            Text(
                                "Video Attached",
                                fontSize = 10.sp,
                                color = Color.White,
                                modifier = Modifier.align(Alignment.BottomCenter).padding(4.dp)
                            )
                        }
                    } else if (message.mediaType == "audio") {
                        // Audio player card inside message bubble
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.AudioFile,
                                contentDescription = "Audio file",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Audio Attached",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    } else {
                        // File metadata card inside message bubble
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.Black.copy(alpha = 0.2f))
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.AttachFile, contentDescription = "doc")
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Attached document",
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // Main response text
                com.example.ui.components.MarkdownContent(
                    text = message.text,
                    baseColor = contentColor,
                    modifier = Modifier.fillMaxWidth()
                )

                // Bottom actions for messages
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (message.isWebResult) {
                        Icon(
                            Icons.Filled.TravelExplore,
                            contentDescription = "Web results used",
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }

                    // Copy text trigger
                    IconButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(message.text))
                            Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Filled.ContentCopy,
                            contentDescription = "Copy",
                            tint = contentColor.copy(alpha = 0.6f),
                            modifier = Modifier.size(14.dp)
                        )
                    }

                    // Speak message trigger
                    val ttsIcon = if (isSpeakingThis) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp
                    val ttsTint = if (isSpeakingThis) MaterialTheme.colorScheme.primary else contentColor.copy(alpha = 0.6f)
                    
                    IconButton(
                        onClick = { viewModel.speakText(message.text) },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = ttsIcon,
                            contentDescription = if (isSpeakingThis) "Stop Speak" else "Speak",
                            tint = ttsTint,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
    }
}

// ==================== PROVIDERS MANAGEMENT TAB ====================
@Composable
fun ProvidersTabScreen(viewModel: MainViewModel, lang: String) {
    val providers by viewModel.aiProviders.collectAsStateWithLifecycle()
    var showAddDialog by remember { mutableStateOf(false) }
    var editingProvider by remember { mutableStateOf<AiProvider?>(null) }
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = Localization.getString("providers", lang),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Button(
                onClick = { showAddDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add")
                Spacer(modifier = Modifier.width(4.dp))
                Text(Localization.getString("add_provider", lang), fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(providers) { provider ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = provider.name,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                Text(
                                    text = "Model: ${provider.modelName}",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                                )
                            }

                            // Enable Toggle
                            Switch(
                                checked = provider.isEnabled,
                                onCheckedChange = {
                                    viewModel.addOrUpdateProvider(provider.copy(isEnabled = it))
                                }
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        when (provider.type) {
                            "LOCAL_GGUF" -> {
                                Text(
                                    text = "📂 File Source: ${provider.baseUrl}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "✅ Local GGUF Loader with Vision support & Custom Tools",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                            "REMOTE_MCP" -> {
                                Text(
                                    text = "🌐 MCP Server: ${provider.baseUrl}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "⚡ Remote Model Context Protocol Server over LAN/HTTP",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                            "GEMINI" -> {
                                Text(
                                    text = "Using platform AI Studio Direct REST Key",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                                )
                            }
                            else -> {
                                Text(
                                    text = "Base URL: ${provider.baseUrl}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${Localization.getString("priority", lang)}: ${provider.priority}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.secondary
                            )

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Test connection action
                                AssistChip(
                                    onClick = {
                                        Toast.makeText(context, "Testing connection to ${provider.name}...", Toast.LENGTH_SHORT).show()
                                        viewModel.testProvider(provider) { result ->
                                            Toast.makeText(context, result, Toast.LENGTH_LONG).show()
                                        }
                                    },
                                    label = { Text("Test", fontSize = 11.sp) },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Filled.PlayArrow,
                                            contentDescription = "Test Connection",
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                )

                                // Edit action
                                AssistChip(
                                    onClick = { editingProvider = provider },
                                    label = { Text("Edit", fontSize = 11.sp) },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Filled.Edit,
                                            contentDescription = "Edit Provider",
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                )

                                // Delete action
                                AssistChip(
                                    onClick = { viewModel.deleteProvider(provider) },
                                    label = { Text("Delete", fontSize = 11.sp, color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Filled.Delete,
                                            contentDescription = "Delete Provider",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    },
                                    colors = AssistChipDefaults.assistChipColors(
                                        labelColor = MaterialTheme.colorScheme.error
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        ProviderAddOrEditDialog(
            lang = lang,
            provider = null,
            onDismiss = { showAddDialog = false },
            onSave = { id, name, type, baseUrl, apiKey, modelName, priority ->
                viewModel.addOrUpdateProvider(
                    AiProvider(
                        id = id,
                        name = name,
                        type = type,
                        baseUrl = baseUrl,
                        apiKey = apiKey,
                        modelName = modelName,
                        isEnabled = true,
                        priority = priority
                    )
                )
                showAddDialog = false
            }
        )
    }

    if (editingProvider != null) {
        ProviderAddOrEditDialog(
            lang = lang,
            provider = editingProvider,
            onDismiss = { editingProvider = null },
            onSave = { id, name, type, baseUrl, apiKey, modelName, priority ->
                viewModel.addOrUpdateProvider(
                    AiProvider(
                        id = id,
                        name = name,
                        type = type,
                        baseUrl = baseUrl,
                        apiKey = apiKey,
                        modelName = modelName,
                        isEnabled = editingProvider?.isEnabled ?: true,
                        priority = priority
                    )
                )
                editingProvider = null
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderAddOrEditDialog(
    lang: String,
    provider: AiProvider? = null,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String, String, String, Int) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var name by remember { mutableStateOf(provider?.name ?: "") }
    var type by remember { mutableStateOf(provider?.type ?: "OPENAI_COMPATIBLE") }
    var baseUrl by remember { mutableStateOf(provider?.baseUrl ?: "https://api.openai.com/v1/") }
    var apiKey by remember { mutableStateOf(provider?.apiKey ?: "") }
    var modelName by remember { mutableStateOf(provider?.modelName ?: "") }
    var priority by remember { mutableStateOf(provider?.priority?.toString() ?: "1") }

    val ggufFilePickerLauncher = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                // ignore
            }
            baseUrl = uri.toString()
            var resolvedName = "My GGUF Model"
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val displayNameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (displayNameIndex >= 0) {
                        resolvedName = cursor.getString(displayNameIndex)
                    }
                }
            }
            modelName = resolvedName
            if (name.isBlank()) {
                name = resolvedName.removeSuffix(".gguf").replaceFirstChar { it.uppercase() }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (provider != null) "Edit Provider" else Localization.getString("add_provider", lang)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(Localization.getString("provider_name", lang)) },
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Provider Engine Type:", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = type == "OPENAI_COMPATIBLE", onClick = { 
                            type = "OPENAI_COMPATIBLE"
                            baseUrl = "https://api.openai.com/v1/"
                        })
                        Text("OpenAI API", fontSize = 13.sp)
                        
                        Spacer(modifier = Modifier.width(16.dp))
                        
                        RadioButton(selected = type == "GEMINI", onClick = { 
                            type = "GEMINI"
                            baseUrl = ""
                        })
                        Text("Gemini Direct", fontSize = 13.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = type == "LOCAL_GGUF", onClick = { 
                            type = "LOCAL_GGUF"
                            baseUrl = ""
                            apiKey = ""
                            modelName = ""
                        })
                        Text("Local GGUF File", fontSize = 13.sp)
                        
                        Spacer(modifier = Modifier.width(16.dp))
                        
                        RadioButton(selected = type == "REMOTE_MCP", onClick = { 
                            type = "REMOTE_MCP"
                            baseUrl = "http://192.168.1.50:8000/"
                        })
                        Text("Remote MCP", fontSize = 13.sp)
                    }
                }

                if (type == "OPENAI_COMPATIBLE") {
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = { Text(Localization.getString("base_url", lang)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (type == "LOCAL_GGUF") {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("GGUF Model File Source:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = baseUrl,
                            onValueChange = { baseUrl = it },
                            label = { Text("Model File URI / Path") },
                            modifier = Modifier.weight(1f),
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp)
                        )
                        Button(
                            onClick = {
                                ggufFilePickerLauncher.launch(arrayOf("*/*"))
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text("Browse", fontSize = 12.sp)
                        }
                    }
                    Text(
                        text = "Provides offline on-device execution, integrated Vision adapters, and MCP function bindings.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

                if (type == "REMOTE_MCP") {
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = { Text("MCP Server LAN / HTTP Endpoint") },
                        placeholder = { Text("e.g. http://192.168.1.50:8000/") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "Connects to remote Model Context Protocol servers over LAN or high-speed HTTP.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

                if (type != "LOCAL_GGUF") {
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text(if (type == "REMOTE_MCP") "API Key / Auth Token (Optional)" else Localization.getString("api_key", lang)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                OutlinedTextField(
                    value = modelName,
                    onValueChange = { modelName = it },
                    label = { Text(if (type == "LOCAL_GGUF") "GGUF Model Name" else Localization.getString("model_name", lang)) },
                    placeholder = { Text("e.g. gpt-4o-mini or llama-3b") },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = priority,
                    onValueChange = { priority = it },
                    label = { Text(Localization.getString("priority", lang)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank() && (modelName.isNotBlank() || type == "LOCAL_GGUF")) {
                        val finalId = provider?.id ?: ("custom_" + name.lowercase().replace(" ", "_") + "_" + System.currentTimeMillis())
                        onSave(finalId, name, type, baseUrl, apiKey, modelName.ifBlank { "Local GGUF Model" }, priority.toIntOrNull() ?: 1)
                    }
                }
            ) {
                Text(Localization.getString("save", lang))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(Localization.getString("cancel", lang))
            }
        }
    )
}

// ==================== MEMORY TAB ====================
@Composable
fun MemoryTabScreen(viewModel: MainViewModel, lang: String) {
    val memories by viewModel.memoryItems.collectAsStateWithLifecycle()
    var contentInput by remember { mutableStateOf("") }
    var categoryInput by remember { mutableStateOf("preference") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = Localization.getString("memory", lang),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Text(
            text = Localization.getString("memory_desc", lang),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            modifier = Modifier.padding(vertical = 8.dp)
        )

        // Memory input form
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = Localization.getString("add_memory", lang),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.secondary
                )

                OutlinedTextField(
                    value = contentInput,
                    onValueChange = { contentInput = it },
                    placeholder = { Text("e.g. User is a programmer and prefers dark theme") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    textStyle = TextStyle(fontSize = 13.sp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = categoryInput == "preference", onClick = { categoryInput = "preference" })
                        Text("Preference", fontSize = 12.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        RadioButton(selected = categoryInput == "fact", onClick = { categoryInput = "fact" })
                        Text("Fact", fontSize = 12.sp)
                    }

                    Button(
                        onClick = {
                            if (contentInput.isNotBlank()) {
                                viewModel.addMemory(contentInput, categoryInput)
                                contentInput = ""
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Text(Localization.getString("save", lang), fontSize = 11.sp)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Memories List
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(memories) { item ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.content,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = "[${item.category.uppercase()}]",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        IconButton(onClick = { viewModel.deleteMemory(item) }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "Delete",
                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ==================== MCP TAB ====================
@Composable
fun McpTabScreen(viewModel: MainViewModel, lang: String) {
    val servers by viewModel.mcpServers.collectAsStateWithLifecycle()
    val tools by viewModel.mcpTools.collectAsStateWithLifecycle()

    var showAddServerDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = Localization.getString("mcp", lang),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Button(
                onClick = { showAddServerDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add Server")
                Spacer(modifier = Modifier.width(4.dp))
                Text(Localization.getString("add_mcp_server", lang), fontSize = 12.sp)
            }
        }

        Text(
            text = Localization.getString("mcp_desc", lang),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            modifier = Modifier.padding(vertical = 8.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            "Registered MCP Servers",
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // Servers Column
        LazyColumn(
            modifier = Modifier.weight(0.4f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(servers) { server ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(server.name, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(server.endpointUrl, fontSize = 11.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
                        }

                        IconButton(onClick = { viewModel.deleteMcpServer(server) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "delete", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            Localization.getString("tools_browsing", lang),
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // Discovered Tools Column
        LazyColumn(
            modifier = Modifier.weight(0.6f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(tools) { tool ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.SettingsInputHdmi, contentDescription = "tool", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(tool.name, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }

                            Switch(
                                checked = tool.isEnabled,
                                onCheckedChange = { viewModel.toggleMcpTool(tool.id, it) }
                            )
                        }

                        Text(tool.description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f), modifier = Modifier.padding(top = 4.dp))
                        Text(
                            "Schema: ${tool.inputSchemaJson}",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }
    }

    if (showAddServerDialog) {
        McpServerAddDialog(
            lang = lang,
            onDismiss = { showAddServerDialog = false },
            onSave = { name, url ->
                viewModel.addMcpServer(name, url)
                showAddServerDialog = false
            },
            onImportFromConfig = { name, command, args, env, endpointUrl ->
                viewModel.addMcpServerFromConfig(name, command, args, env, endpointUrl)
                showAddServerDialog = false
            }
        )
    }
}

data class ParsedMcpServerConfig(
    val name: String,
    val command: String,
    val args: List<String>,
    val env: Map<String, String>
)

fun parseMcpJsonConfig(jsonStr: String): List<ParsedMcpServerConfig> {
    val results = mutableListOf<ParsedMcpServerConfig>()
    if (jsonStr.isBlank()) return results
    try {
        val root = org.json.JSONObject(jsonStr.trim())
        if (root.has("mcpServers")) {
            val serversObj = root.getJSONObject("mcpServers")
            val keys = serversObj.keys()
            while (keys.hasNext()) {
                val name = keys.next()
                val serverObj = serversObj.getJSONObject(name)
                val command = serverObj.optString("command", "")
                val argsArray = serverObj.optJSONArray("args")
                val args = mutableListOf<String>()
                if (argsArray != null) {
                    for (i in 0 until argsArray.length()) {
                        args.add(argsArray.getString(i))
                    }
                }
                val envObj = serverObj.optJSONObject("env")
                val env = mutableMapOf<String, String>()
                if (envObj != null) {
                    val envKeys = envObj.keys()
                    while (envKeys.hasNext()) {
                        val envKey = envKeys.next()
                        env[envKey] = envObj.getString(envKey)
                    }
                }
                results.add(ParsedMcpServerConfig(name, command, args, env))
            }
        } else {
            // Check if it represents a single server config
            val command = root.optString("command", "")
            if (command.isNotBlank()) {
                val name = root.optString("name", "Imported MCP Server").ifBlank { "Imported MCP Server" }
                val argsArray = root.optJSONArray("args")
                val args = mutableListOf<String>()
                if (argsArray != null) {
                    for (i in 0 until argsArray.length()) {
                        args.add(argsArray.getString(i))
                    }
                }
                val envObj = root.optJSONObject("env")
                val env = mutableMapOf<String, String>()
                if (envObj != null) {
                    val envKeys = envObj.keys()
                    while (envKeys.hasNext()) {
                        val envKey = envKeys.next()
                        env[envKey] = envObj.getString(envKey)
                    }
                }
                results.add(ParsedMcpServerConfig(name, command, args, env))
            }
        }
    } catch (e: Exception) {
        // Find if there is a JSON block enclosed in braces (robust fuzzy fallback parsing)
        try {
            val startIdx = jsonStr.indexOf("{")
            val endIdx = jsonStr.lastIndexOf("}")
            if (startIdx != -1 && endIdx != -1 && endIdx > startIdx) {
                val substring = jsonStr.substring(startIdx, endIdx + 1)
                return parseMcpJsonConfig(substring)
            }
        } catch (e2: Exception) {
            // Ignore
        }
    }
    return results
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpServerAddDialog(
    lang: String,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
    onImportFromConfig: (String, String, List<String>, Map<String, String>, String) -> Unit
) {
    var activeTab by remember { mutableStateOf(0) } // 0 = URL, 1 = Config paste
    
    // URL Mode state
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }

    // Paste Config Mode state
    var pastedJson by remember { mutableStateOf("") }
    var bridgeUrlOverride by remember { mutableStateOf("http://192.168.1.100:8000/mcp") }
    
    val parsedServers = remember(pastedJson) { parseMcpJsonConfig(pastedJson) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Localization.getString("add_mcp_server", lang)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                // Tab Selection Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val tabModifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                    
                    Box(
                        modifier = tabModifier
                            .background(if (activeTab == 0) MaterialTheme.colorScheme.primary else Color.Transparent)
                            .clickable { activeTab = 0 }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (lang == "ru") "Простой URL" else "Simple URL",
                            color = if (activeTab == 0) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                    
                    Box(
                        modifier = tabModifier
                            .background(if (activeTab == 1) MaterialTheme.colorScheme.primary else Color.Transparent)
                            .clickable { activeTab = 1 }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (lang == "ru") "Конфиг (mcp.so)" else "Pasted Config",
                            color = if (activeTab == 1) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }
                
                if (activeTab == 0) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(Localization.getString("server_name", lang)) },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text(Localization.getString("endpoint_url", lang)) },
                        placeholder = { Text("http://192.168.1.100:8000/mcp") },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Text(
                        text = if (lang == "ru") "Вставьте JSON конфиг (Claude Desktop / Cursor format):" else "Paste JSON config (Claude Desktop / Cursor / mcp.so):",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    
                    OutlinedTextField(
                        value = pastedJson,
                        onValueChange = { pastedJson = it },
                        placeholder = { Text("{\n  \"mcpServers\": {\n    \"weather\": {\n      \"command\": \"npx\",\n      \"args\": [\"-y\", \"@mcp/server\"]\n    }\n  }\n}") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp),
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    )
                    
                    OutlinedTextField(
                        value = bridgeUrlOverride,
                        onValueChange = { bridgeUrlOverride = it },
                        label = { Text(if (lang == "ru") "URL удаленного моста / SSE" else "Bridge/SSE Endpoint URL") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    if (pastedJson.isNotBlank()) {
                        if (parsedServers.isNotEmpty()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f))
                                    .padding(8.dp)
                            ) {
                                Text(
                                    text = if (lang == "ru") "✓ Распознано серверов: ${parsedServers.size}" else "✓ Detected ${parsedServers.size} server configurations:",
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                parsedServers.forEach { s ->
                                    Text(
                                        text = "  • ${s.name} (${s.command})",
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        } else {
                            Text(
                                text = if (lang == "ru") "⚠ Ожидание корректного JSON..." else "⚠ Awaiting valid JSON block...",
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (activeTab == 0) {
                        if (name.isNotBlank() && url.isNotBlank()) {
                            onSave(name, url)
                        }
                    } else {
                        if (parsedServers.isNotEmpty()) {
                            parsedServers.forEach { server ->
                                onImportFromConfig(
                                    server.name,
                                    server.command,
                                    server.args,
                                    server.env,
                                    bridgeUrlOverride
                                )
                            }
                        }
                    }
                },
                enabled = if (activeTab == 0) (name.isNotBlank() && url.isNotBlank()) else parsedServers.isNotEmpty()
            ) {
                Text(if (activeTab == 1) (if (lang == "ru") "Импортировать" else "Import") else Localization.getString("save", lang))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(Localization.getString("cancel", lang))
            }
        }
    )
}

// ==================== SETTINGS TAB ====================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTabScreen(viewModel: MainViewModel, lang: String) {
    val context = LocalContext.current
    val systemPrompt by viewModel.systemPrompt.collectAsStateWithLifecycle()
    val ttsAutoplay by viewModel.ttsAutoplay.collectAsStateWithLifecycle()
    val autoCopyEnabled by viewModel.autoCopyEnabled.collectAsStateWithLifecycle()
    val darkTheme by viewModel.darkTheme.collectAsStateWithLifecycle()
    val autoNotifyEnabled by viewModel.autoNotifyEnabled.collectAsStateWithLifecycle()
    
    val observeMediaEnabled by viewModel.observeMediaEnabled.collectAsStateWithLifecycle()
    val observeMediaFolder by viewModel.observeMediaFolder.collectAsStateWithLifecycle()
    val observeMediaPrompt by viewModel.observeMediaPrompt.collectAsStateWithLifecycle()

    val wpUrl by viewModel.wpUrl.collectAsStateWithLifecycle()
    val wpUser by viewModel.wpUser.collectAsStateWithLifecycle()
    val wpAppPass by viewModel.wpAppPass.collectAsStateWithLifecycle()
    val wpAutoPost by viewModel.wpAutoPost.collectAsStateWithLifecycle()

    val btAutoSendEnabled by viewModel.btAutoSendEnabled.collectAsStateWithLifecycle()
    val btSendUserMessages by viewModel.btSendUserMessages.collectAsStateWithLifecycle()
    val btSendAiResponses by viewModel.btSendAiResponses.collectAsStateWithLifecycle()
    val btSelectedDevices by viewModel.btSelectedDevices.collectAsStateWithLifecycle()
    val btDeviceConfigs by viewModel.btDeviceConfigs.collectAsStateWithLifecycle()
    val btPairedDevices by viewModel.btPairedDevices.collectAsStateWithLifecycle()
    val btStatusLog by viewModel.btStatusLog.collectAsStateWithLifecycle()
    val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()

    var promptInput by remember { mutableStateOf(systemPrompt) }
    var ttsType by remember { mutableStateOf("local") }
    var openaiUrl by remember { mutableStateOf("") }
    var openaiKey by remember { mutableStateOf("") }
    var openaiModel by remember { mutableStateOf("") }
    var openaiVoice by remember { mutableStateOf("") }
    var ttsVoiceList by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var newVoiceId by remember { mutableStateOf("") }
    var newVoiceName by remember { mutableStateOf("") }
    var voiceDropdownExpanded by remember { mutableStateOf(false) }
    
    var folderInput by remember { mutableStateOf(observeMediaFolder) }
    var mediaPromptInput by remember { mutableStateOf(observeMediaPrompt) }

    var wpUrlInput by remember { mutableStateOf(wpUrl) }
    var wpUserInput by remember { mutableStateOf(wpUser) }
    var wpPassInput by remember { mutableStateOf(wpAppPass) }
    var companionIpInput by remember { mutableStateOf("") }
    var exportJsonContent by remember { mutableStateOf("") }
    var exportMimeType by remember { mutableStateOf("application/json") }

    // Preconfigured device form state
    var devNameInput by remember { mutableStateOf("") }
    var devAddrInput by remember { mutableStateOf("") }
    var devTypeInput by remember { mutableStateOf("esp32") }
    var devConnectionInput by remember { mutableStateOf("wifi") }

    val btPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) {
            viewModel.refreshBtPairedDevices()
        } else {
            Toast.makeText(context, if (lang == "ru") "Разрешение Bluetooth отклонено" else "Bluetooth permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    val createDocumentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(exportMimeType)) { uri ->
        uri?.let {
            context.contentResolver.openOutputStream(it)?.use { os -> os.write(exportJsonContent.toByteArray()) }
            Toast.makeText(context, if (lang == "ru") "Экспортировано!" else "Exported!", Toast.LENGTH_SHORT).show()
        }
    }

    val importFilePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            try {
                val json = context.contentResolver.openInputStream(it)?.bufferedReader()?.use { r -> r.readText() }
                if (json != null) {
                    viewModel.importSettings(json) { success ->
                        Toast.makeText(context, if (success) if (lang == "ru") "Импортировано!" else "Imported!" else if (lang == "ru") "Ошибка" else "Failed", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) { Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show() }
        }
    }

    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            viewModel.updateObserveMediaEnabled(true)
            Toast.makeText(context, if (lang == "ru") "Разрешение предоставлено! Мониторинг запущен." else "Permission granted! Monitoring started.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, if (lang == "ru") "В разрешении отказано. Мониторинг отключен." else "Permission denied. Monitoring disabled.", Toast.LENGTH_SHORT).show()
            viewModel.updateObserveMediaEnabled(false)
        }
    }

    LaunchedEffect(observeMediaFolder) {
        folderInput = observeMediaFolder
    }

    LaunchedEffect(observeMediaPrompt) {
        mediaPromptInput = observeMediaPrompt
    }

    LaunchedEffect(systemPrompt) {
        promptInput = systemPrompt
    }

    LaunchedEffect(wpUrl, wpUser, wpAppPass) {
        wpUrlInput = wpUrl
        wpUserInput = wpUser
        wpPassInput = wpAppPass
    }

    LaunchedEffect(Unit) {
        viewModel.getCustomTtsSetting("tts_type", "local") { ttsType = it }
        viewModel.getCustomTtsSetting("openai_tts_url", "https://api.openai.com/v1/") { openaiUrl = it }
        viewModel.getCustomTtsSetting("openai_tts_key", "") { openaiKey = it }
        viewModel.getCustomTtsSetting("openai_tts_model", "tts-1") { openaiModel = it }
        viewModel.getCustomTtsSetting("openai_tts_voice", "alloy") { openaiVoice = it }
        viewModel.getCustomTtsSetting("tts_voice_list", "[]") { json ->
            try {
                val arr = org.json.JSONArray(json)
                val list = mutableListOf<Pair<String, String>>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    list.add(Pair(obj.getString("id"), obj.getString("name")))
                }
                ttsVoiceList = list
            } catch (e: Exception) { }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = Localization.getString("settings", lang),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        // Language Switcher (EN, DE, RU)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = Localization.getString("language", lang),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.secondary
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    val langs = listOf("en" to "English 🇬🇧", "de" to "Deutsch 🇩🇪", "ru" to "Русский 🇷🇺")
                    langs.forEach { (code, name) ->
                        val isSelected = code == lang
                        FilterChip(
                            selected = isSelected,
                            onClick = { viewModel.updateLanguage(code) },
                            label = { Text(name) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                            )
                        )
                    }
                }
            }
        }

        // Theme Switcher (Light / Dark)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = Localization.getString("theme_title", lang),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.secondary
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    val themes = listOf(
                        false to Localization.getString("theme_light", lang),
                        true to Localization.getString("theme_dark", lang)
                    )
                    themes.forEach { (isDark, name) ->
                        val isSelected = isDark == darkTheme
                        FilterChip(
                            selected = isSelected,
                            onClick = { viewModel.toggleDarkTheme(isDark) },
                            label = { Text(name) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                            )
                        )
                    }
                }
            }
        }

        // Statusbar Notifications Card
        val notificationPermissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (isGranted) {
                viewModel.toggleAutoNotify(true)
                Toast.makeText(context, if (lang == "ru") "Разрешение на уведомления получено!" else "Notification permission granted!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, if (lang == "ru") "Разрешение отклонено" else "Permission denied", Toast.LENGTH_SHORT).show()
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.NotificationsActive,
                        contentDescription = "Notifications",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(
                        text = Localization.getString("auto_notify_title", lang),
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = Localization.getString("auto_notify_desc", lang),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (lang == "ru") "Авто-публикация в статус-бар" else "Auto-post to Statusbar",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Switch(
                        checked = autoNotifyEnabled,
                        onCheckedChange = { checked ->
                            if (checked && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                if (androidx.core.content.ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.POST_NOTIFICATIONS
                                    ) != PackageManager.PERMISSION_GRANTED
                                ) {
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    return@Switch
                                }
                            }
                            viewModel.toggleAutoNotify(checked)
                        }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
                            androidx.core.content.ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.POST_NOTIFICATIONS
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            viewModel.sendTestNotification()
                            Toast.makeText(context, if (lang == "ru") "Тестовое уведомление отправлено!" else "Test notification sent!", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Filled.Notifications,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(Localization.getString("send_test_notification", lang))
                }
            }
        }

        // Custom System Instruction Editor
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = Localization.getString("system_prompt", lang),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.secondary
                )

                OutlinedTextField(
                    value = promptInput,
                    onValueChange = { promptInput = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .height(110.dp),
                    textStyle = TextStyle(fontSize = 12.sp)
                )

                Button(
                    onClick = { viewModel.updateSystemPrompt(promptInput) },
                    modifier = Modifier.align(Alignment.End),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text(Localization.getString("save", lang), fontSize = 12.sp)
                }
            }
        }

        // TTS Autoplay Toggle
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = Localization.getString("autoplay_tts", lang),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Text(
                        text = Localization.getString("autoplay_on", lang),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                    )
                }

                Switch(
                    checked = ttsAutoplay,
                    onCheckedChange = { viewModel.toggleTtsAutoplay(it) }
                )
            }
            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = if (lang == "ru") "Авто-копирование" else "Auto-copy response",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Text(
                        text = if (lang == "ru") "Автоматически копировать ответ" else "Automatically copy AI response",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                    )
                }

                Switch(
                    checked = autoCopyEnabled,
                    onCheckedChange = { viewModel.updateAutoCopyEnabled(it) }
                )
            }
        }

        // WordPress Integration Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = Localization.getString("wp_settings_title", lang),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.secondary
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = wpUrlInput,
                    onValueChange = { wpUrlInput = it },
                    label = { Text(Localization.getString("wp_url_label", lang)) },
                    placeholder = { Text("https://your-site.com") },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(fontSize = 12.sp)
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = wpUserInput,
                    onValueChange = { wpUserInput = it },
                    label = { Text(Localization.getString("wp_user_label", lang)) },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(fontSize = 12.sp)
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = wpPassInput,
                    onValueChange = { wpPassInput = it },
                    label = { Text(Localization.getString("wp_app_pass_label", lang)) },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(fontSize = 12.sp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = Localization.getString("wp_auto_post_label", lang),
                        fontSize = 12.sp
                    )
                    Switch(
                        checked = wpAutoPost,
                        onCheckedChange = { viewModel.updateWpSettings(wpUrlInput, wpUserInput, wpPassInput, it) }
                    )
                }

                Button(
                    onClick = { viewModel.updateWpSettings(wpUrlInput, wpUserInput, wpPassInput, wpAutoPost) },
                    modifier = Modifier.align(Alignment.End),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text(Localization.getString("save", lang), fontSize = 12.sp)
                }

                Spacer(modifier = Modifier.height(8.dp))

                var wpTestResult by remember { mutableStateOf("") }
                var wpTestLoading by remember { mutableStateOf(false) }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            viewModel.updateWpSettings(wpUrlInput, wpUserInput, wpPassInput, wpAutoPost)
                            wpTestLoading = true
                            wpTestResult = ""
                            viewModel.testWordPressConnectionDirect(wpUrlInput, wpUserInput, wpPassInput) { result ->
                                wpTestResult = result
                                wpTestLoading = false
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !wpTestLoading
                    ) {
                        Text("Test Connection", fontSize = 11.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            viewModel.updateWpSettings(wpUrlInput, wpUserInput, wpPassInput, wpAutoPost)
                            wpTestLoading = true
                            wpTestResult = ""
                            viewModel.createTestWordPressPostDirect(wpUrlInput, wpUserInput, wpPassInput) { result ->
                                wpTestResult = result
                                wpTestLoading = false
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !wpTestLoading
                    ) {
                        Text("Test Post", fontSize = 11.sp)
                    }
                }

                if (wpTestResult.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = wpTestResult,
                        fontSize = 11.sp,
                        color = if (wpTestResult.startsWith("OK")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        // Media Folder / Gallery Observation Settings
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = Localization.getString("observe_media_title", lang),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Text(
                            text = Localization.getString("observe_media_desc", lang),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Switch(
                        checked = observeMediaEnabled,
                        onCheckedChange = { checked ->
                            if (checked) {
                                val permissionsNeeded = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                    arrayOf(
                                        android.Manifest.permission.READ_MEDIA_IMAGES,
                                        android.Manifest.permission.READ_MEDIA_VIDEO
                                    )
                                } else {
                                    arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                                }
                                
                                val hasPermissions = permissionsNeeded.all { perm ->
                                    ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
                                }
                                
                                if (hasPermissions) {
                                    viewModel.updateObserveMediaEnabled(true)
                                } else {
                                    storagePermissionLauncher.launch(permissionsNeeded)
                                }
                            } else {
                                viewModel.updateObserveMediaEnabled(false)
                            }
                        }
                    )
                }

                if (observeMediaEnabled) {
                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = folderInput,
                        onValueChange = { folderInput = it },
                        label = { Text(Localization.getString("observe_folder_label", lang)) },
                        placeholder = { Text(Localization.getString("observe_media_folder_placeholder", lang)) },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(fontSize = 12.sp)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = mediaPromptInput,
                        onValueChange = { mediaPromptInput = it },
                        label = { Text(Localization.getString("observe_prompt_label", lang)) },
                        placeholder = { Text("e.g. Please analyze this image.") },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(fontSize = 12.sp)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = {
                            viewModel.updateObserveMediaFolder(folderInput)
                            viewModel.updateObserveMediaPrompt(mediaPromptInput)
                        },
                        modifier = Modifier.align(Alignment.End),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text(Localization.getString("save", lang), fontSize = 12.sp)
                    }
                }
            }
        }

        // ─── Preconfigured Devices (Bluetooth / ESP32 / Display) ───
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Bluetooth, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (lang == "ru") "Устройства (BT / ESP32)" else "Devices (BT / ESP32)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = if (lang == "ru")
                        "AI ответы автоматически отправляются на выбранные устройства."
                    else
                        "AI responses are automatically sent to the selected devices.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Auto-send toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (lang == "ru") "Авто-отправка на устройства" else "Auto-send to devices",
                        fontSize = 12.sp
                    )
                    Switch(
                        checked = btAutoSendEnabled,
                        onCheckedChange = { viewModel.toggleBtAutoSend(it) }
                    )
                }

                if (btAutoSendEnabled) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (lang == "ru") "Отправлять мои сообщения" else "Send my messages",
                            fontSize = 11.sp
                        )
                        Switch(
                            checked = btSendUserMessages,
                            onCheckedChange = { viewModel.toggleBtSendUserMessages(it) }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (lang == "ru") "Отправлять ответы AI" else "Send AI responses",
                            fontSize = 11.sp
                        )
                        Switch(
                            checked = btSendAiResponses,
                            onCheckedChange = { viewModel.toggleBtSendAiResponses(it) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Refresh paired devices button
                Button(
                    onClick = {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                            val perms = mutableListOf(Manifest.permission.BLUETOOTH_CONNECT)
                            val notGranted = perms.any {
                                androidx.core.content.ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                            }
                            if (notGranted) {
                                btPermissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
                            } else {
                                viewModel.refreshBtPairedDevices()
                            }
                        } else {
                            viewModel.refreshBtPairedDevices()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.BluetoothSearching, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (lang == "ru") "Обновить спаренные устройства" else "Refresh paired devices", fontSize = 12.sp)
                }

                // Add device form
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = if (lang == "ru") "Добавить устройство" else "Add device",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )

                OutlinedTextField(
                    value = devNameInput,
                    onValueChange = { devNameInput = it },
                    label = { Text(if (lang == "ru") "Имя" else "Name", fontSize = 12.sp) },
                    placeholder = { Text("OmniChat-CYD") },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(fontSize = 13.sp),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(4.dp))

                OutlinedTextField(
                    value = devAddrInput,
                    onValueChange = { devAddrInput = it },
                    label = { Text(if (lang == "ru") "MAC / IP" else "MAC / IP", fontSize = 12.sp) },
                    placeholder = { Text("192.168.1.100") },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(fontSize = 13.sp),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Device type selector
                    Column(modifier = Modifier.weight(1f)) {
                        Text(if (lang == "ru") "Тип" else "Type", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Devices, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            var devTypeMenuExpanded by remember { mutableStateOf(false) }
                            Box {
                                OutlinedButton(
                                    onClick = { devTypeMenuExpanded = true },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(devTypeInput.uppercase(), fontSize = 11.sp)
                                }
                                DropdownMenu(
                                    expanded = devTypeMenuExpanded,
                                    onDismissRequest = { devTypeMenuExpanded = false }
                                ) {
                                    listOf("esp32", "display", "android").forEach { t ->
                                        DropdownMenuItem(
                                            text = { Text(t.uppercase(), fontSize = 12.sp) },
                                            onClick = { devTypeInput = t; devTypeMenuExpanded = false }
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(if (lang == "ru") "Соединение" else "Connection", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Link, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            var connMenuExpanded by remember { mutableStateOf(false) }
                            Box {
                                OutlinedButton(
                                    onClick = { connMenuExpanded = true },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(devConnectionInput.uppercase(), fontSize = 11.sp)
                                }
                                DropdownMenu(
                                    expanded = connMenuExpanded,
                                    onDismissRequest = { connMenuExpanded = false }
                                ) {
                                    listOf("wifi", "bluetooth").forEach { c ->
                                        DropdownMenuItem(
                                            text = { Text(c.uppercase(), fontSize = 12.sp) },
                                            onClick = { devConnectionInput = c; connMenuExpanded = false }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = {
                        if (devAddrInput.isNotBlank()) {
                            val key = if (devConnectionInput == "wifi") "udp_${devAddrInput.replace('.', '_')}" else devAddrInput
                            val config = BtDeviceConfig(
                                deviceType = devTypeInput,
                                connectionType = devConnectionInput,
                                ipAddress = if (devConnectionInput == "wifi") devAddrInput else "",
                                name = devNameInput.ifBlank { devAddrInput }
                            )
                            viewModel.addConfiguredDevice(key, config, true)
                            devNameInput = ""
                            devAddrInput = ""
                            Toast.makeText(context, if (lang == "ru") "Устройство добавлено!" else "Device added!", Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = devAddrInput.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (lang == "ru") "Добавить устройство" else "Add device", fontSize = 12.sp)
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Configured devices list
                val configuredKeys = btDeviceConfigs.keys.map { it as String }
                if (configuredKeys.isNotEmpty()) {
                    Text(
                        text = if (lang == "ru") "Настроенные устройства:" else "Configured devices:",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )

                    LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
                        items(configuredKeys) { key ->
                            val cfg = btDeviceConfigs[key] ?: BtDeviceConfig.defaultConfig()
                            val isSelected = btSelectedDevices.contains(key)
                            val displayName = cfg.name.ifBlank { key.ifBlank { "Device" } }
                            val subtitle = when (cfg.connectionType) {
                                "bluetooth" -> "BT • ${key}"
                                else -> "${cfg.deviceType.uppercase()} • ${cfg.ipAddress.ifBlank { key }}"
                            }

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                                    .padding(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = displayName,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = subtitle,
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Switch(
                                        checked = isSelected,
                                        onCheckedChange = { viewModel.setDeviceSelected(key, it) }
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    AssistChip(
                                        onClick = { viewModel.sendTestMessageToDevice(key) },
                                        label = { Text(if (lang == "ru") "Тест" else "Test", fontSize = 10.sp) },
                                        leadingIcon = { Icon(Icons.Filled.Send, contentDescription = null, modifier = Modifier.size(12.dp)) },
                                        modifier = Modifier.weight(1f),
                                        colors = AssistChipDefaults.assistChipColors(
                                            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
                                        )
                                    )
                                    AssistChip(
                                        onClick = { viewModel.removeConfiguredDevice(key) },
                                        label = { Text(if (lang == "ru") "Удалить" else "Delete", fontSize = 10.sp) },
                                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(12.dp)) },
                                        modifier = Modifier.weight(1f),
                                        colors = AssistChipDefaults.assistChipColors(
                                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                                        )
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Text(
                        text = if (lang == "ru") "Нет настроенных устройств" else "No devices configured",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }

                // Paired Bluetooth devices (auto-added)
                if (btPairedDevices.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = if (lang == "ru") "Спаренные BT устройства (авто-добавляются):" else "Paired BT devices (auto-added):",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    btPairedDevices.forEach { device ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Bluetooth, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(device.name, fontSize = 11.sp)
                                Text(device.macAddress, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                            }
                        }
                    }
                }

                // Status log
                if (btStatusLog.isNotBlank()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = if (lang == "ru") "Журнал:" else "Log:",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = btStatusLog,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        lineHeight = 14.sp
                    )
                }
            }
        }

        // Companion IP Address
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Wifi, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (lang == "ru") "Companion (WiFi/UDP)" else "Companion (WiFi/UDP)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = { viewModel.autoDiscoverCompanions() },
                    enabled = !isScanning,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                ) {
                    if (isScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (lang == "ru") "Сканирование..." else "Scanning...", fontSize = 12.sp)
                    } else {
                        Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (lang == "ru") "Найтиcompanions в сети" else "Scan Network", fontSize = 12.sp)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = if (lang == "ru") "IP-адрес companion приложения" else "Companion app IP address",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )

                Spacer(modifier = Modifier.height(4.dp))

                OutlinedTextField(
                    value = companionIpInput,
                    onValueChange = { companionIpInput = it },
                    label = { Text("IP Address", fontSize = 12.sp) },
                    placeholder = { Text("192.168.1.100") },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(fontSize = 14.sp),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(4.dp))

                Button(
                    onClick = {
                        if (companionIpInput.isNotBlank()) {
                            viewModel.addUdpDevice(companionIpInput)
                            companionIpInput = ""
                            Toast.makeText(context, if (lang == "ru") "Companion добавлен!" else "Companion added!", Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = companionIpInput.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (lang == "ru") "Добавить Companion" else "Add Companion", fontSize = 12.sp)
                }

                val udpCompanions: List<Pair<String, String>> = btSelectedDevices
                    .filter { it.startsWith("udp_") && btDeviceConfigs[it]?.connectionType == "wifi" }
                    .map { mac: String -> mac to (btDeviceConfigs[mac]?.ipAddress ?: mac.removePrefix("udp_").replace('_', '.')) }

                if (udpCompanions.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = if (lang == "ru") "Добавленные companions:" else "Added companions:",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    udpCompanions.forEach { (mac, ip) ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                                .padding(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.Wifi, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = ip,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = { viewModel.removeUdpDevice(mac) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(Icons.Filled.Close, contentDescription = "Remove", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                AssistChip(
                                    onClick = { viewModel.checkCompanionConnection(ip) },
                                    label = { Text(if (lang == "ru") "Проверить" else "Check", fontSize = 10.sp) },
                                    leadingIcon = {
                                        Icon(Icons.Filled.Link, contentDescription = null, modifier = Modifier.size(12.dp))
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                                    )
                                )
                                AssistChip(
                                    onClick = { viewModel.sendTestMessageToCompanion(ip) },
                                    label = { Text(if (lang == "ru") "Тест" else "Test", fontSize = 10.sp) },
                                    leadingIcon = {
                                        Icon(Icons.Filled.Send, contentDescription = null, modifier = Modifier.size(12.dp))
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

        // Backup & Restore
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = if (lang == "ru") "Резервное копирование" else "Backup & Restore",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.secondary
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = if (lang == "ru") "Экспорт/импорт всех настроек в JSON" else "Export/import all settings to JSON",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            viewModel.exportSettings { json ->
                                if (json != null) {
                                    exportJsonContent = json
                                    exportMimeType = "application/json"
                                    val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
                                    createDocumentLauncher.launch("omnichat_$timestamp.json")
                                }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (lang == "ru") "Экспорт" else "Export", fontSize = 11.sp)
                    }
                    OutlinedButton(
                        onClick = { importFilePickerLauncher.launch(arrayOf("application/json", "text/plain")) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (lang == "ru") "Импорт" else "Import", fontSize = 11.sp)
                    }
                }
            }
        }

        // OpenAI Custom TTS Configs
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = Localization.getString("tts_settings", lang),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.secondary
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = ttsType == "local", onClick = {
                        ttsType = "local"
                        viewModel.saveCustomTtsSetting("tts_type", "local")
                    })
                    Text(Localization.getString("tts_type_local", lang), fontSize = 12.sp)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = ttsType == "openai", onClick = {
                        ttsType = "openai"
                        viewModel.saveCustomTtsSetting("tts_type", "openai")
                    })
                    Text(Localization.getString("tts_type_openai", lang), fontSize = 12.sp)
                }

                if (ttsType == "openai") {
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = openaiUrl,
                        onValueChange = {
                            openaiUrl = it
                            viewModel.saveCustomTtsSetting("openai_tts_url", it)
                        },
                        label = { Text("TTS Base URL") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = openaiKey,
                        onValueChange = {
                            openaiKey = it
                            viewModel.saveCustomTtsSetting("openai_tts_key", it)
                        },
                        label = { Text("TTS API Key") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = openaiModel,
                        onValueChange = {
                            openaiModel = it
                            viewModel.saveCustomTtsSetting("openai_tts_model", it)
                        },
                        label = { Text("TTS Model ID") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Voice Selector Dropdown
                    val currentVoiceName = ttsVoiceList.find { it.first == openaiVoice }?.second ?: openaiVoice
                    ExposedDropdownMenuBox(
                        expanded = voiceDropdownExpanded,
                        onExpandedChange = { voiceDropdownExpanded = !voiceDropdownExpanded }
                    ) {
                        OutlinedTextField(
                            value = currentVoiceName,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("TTS Voice") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = voiceDropdownExpanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor()
                        )
                        ExposedDropdownMenu(
                            expanded = voiceDropdownExpanded,
                            onDismissRequest = { voiceDropdownExpanded = false }
                        ) {
                            ttsVoiceList.forEach { voice ->
                                DropdownMenuItem(
                                    text = { Text("${voice.second} (${voice.first})") },
                                    onClick = {
                                        openaiVoice = voice.first
                                        viewModel.saveCustomTtsSetting("openai_tts_voice", voice.first)
                                        voiceDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Add new voice
                    OutlinedTextField(
                        value = newVoiceId,
                        onValueChange = { newVoiceId = it },
                        label = { Text("New Voice ID") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newVoiceName,
                        onValueChange = { newVoiceName = it },
                        label = { Text("New Voice Name") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            if (newVoiceId.isNotBlank() && newVoiceName.isNotBlank()) {
                                val updated = ttsVoiceList + Pair(newVoiceId.trim(), newVoiceName.trim())
                                ttsVoiceList = updated
                                val arr = org.json.JSONArray()
                                updated.forEach { v ->
                                    val obj = org.json.JSONObject()
                                    obj.put("id", v.first)
                                    obj.put("name", v.second)
                                    arr.put(obj)
                                }
                                viewModel.saveCustomTtsSetting("tts_voice_list", arr.toString())
                                newVoiceId = ""
                                newVoiceName = ""
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = newVoiceId.isNotBlank() && newVoiceName.isNotBlank()
                    ) {
                        Text("Add Voice")
                    }
                }
            }
        }
    }
}

// ==================== TOOL BUTTON ====================
@Composable
fun ToolButton(
    icon: ImageVector,
    label: String,
    tint: Color? = null,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint ?: MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            fontSize = 9.sp,
            color = tint ?: MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
        )
    }
}

// ==================== VISUAL EFFECTS ====================
@Composable
fun PulsingAudioIndicator(lang: String) {
    val infiniteTransition = rememberInfiniteTransition()
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.5f))
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .drawBehind {
                        drawCircle(
                            color = Color(0xFFEF4444),
                            radius = size.minDimension / 2 * pulseScale,
                            style = Stroke(width = 3.dp.toPx())
                        )
                    }
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                Localization.getString("recording", lang),
                color = Color(0xFFEF4444),
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
        }
    }
}
