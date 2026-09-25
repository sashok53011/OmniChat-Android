package com.example.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.FileObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.db.AppDatabase
import com.example.data.model.ChatMessage
import com.example.data.repository.AppRepository
import com.example.util.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

class MediaObserverService : Service() {

    private val TAG = "MediaObserverService"
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var galleryImagesObserver: ContentObserver? = null
    private var galleryVideosObserver: ContentObserver? = null
    private var folderFileObserver: FileObserver? = null

    private var observerStartTime: Long = 0L
    private var lastObservedMediaUri: Uri? = null
    private var lastObservedMediaTime: Long = 0L
    private val processedImageHashes = mutableSetOf<String>()

    private val imageQueue = java.util.concurrent.ConcurrentLinkedQueue<Uri>()
    @Volatile private var isProcessing = false

    private lateinit var repository: AppRepository

    companion object {
        const val FOREGROUND_NOTIFICATION_ID = 8881
        const val FOREGROUND_CHANNEL_ID = "omnichat_observer_service_channel"

        fun startService(context: Context) {
            val intent = Intent(context, MediaObserverService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, MediaObserverService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val db = AppDatabase.getDatabase(applicationContext)
        repository = AppRepository(db.appDao(), applicationContext)

        startForegroundNotification()
        observerStartTime = System.currentTimeMillis()
        setupMediaObserver()
        Log.d(TAG, "MediaObserverService created & started foreground")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "MediaObserverService onStartCommand triggered")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        unregisterObservers()
        serviceScope.cancel()
        Log.d(TAG, "MediaObserverService destroyed")
    }

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelName = "OmniChat AI — Мониторинг папок"
            val channel = NotificationChannel(
                FOREGROUND_CHANNEL_ID,
                channelName,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Фоновый сервис отслеживания новых изображений в целевых папках"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, FOREGROUND_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("OmniChat AI — Фоновый мониторинг")
            .setContentText("Мониторинг новых файлов в папке активен 🔍")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(FOREGROUND_NOTIFICATION_ID, notification)
    }

    private fun setupMediaObserver() {
        unregisterObservers()

        val handler = Handler(Looper.getMainLooper())

        // 1. Setup MediaStore ContentObservers
        try {
            galleryImagesObserver = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    super.onChange(selfChange, uri)
                    handleNewMediaDetected()
                }
            }

            galleryVideosObserver = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    super.onChange(selfChange, uri)
                    handleNewMediaDetected()
                }
            }

            contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true,
                galleryImagesObserver!!
            )
            contentResolver.registerContentObserver(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                true,
                galleryVideosObserver!!
            )
            Log.d(TAG, "Service: MediaStore ContentObservers registered")
        } catch (e: Exception) {
            Log.e(TAG, "Service: Error registering ContentObservers", e)
        }

        // 2. Setup Custom Directory FileObserver
        serviceScope.launch {
            val customPath = repository.getSettingValue("observe_media_folder", "")
            if (customPath.isNotBlank()) {
                val folder = File(customPath)
                if (folder.exists() && folder.isDirectory) {
                    try {
                        val flags = FileObserver.CREATE or FileObserver.CLOSE_WRITE
                        folderFileObserver = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            object : FileObserver(folder, flags) {
                                override fun onEvent(event: Int, path: String?) {
                                    if (path != null) {
                                        handleCustomFolderFile(File(folder, path))
                                    }
                                }
                            }
                        } else {
                            @Suppress("DEPRECATION")
                            object : FileObserver(customPath, flags) {
                                override fun onEvent(event: Int, path: String?) {
                                    if (path != null) {
                                        handleCustomFolderFile(File(customPath, path))
                                    }
                                }
                            }
                        }
                        folderFileObserver?.startWatching()
                        Log.d(TAG, "Service: FileObserver started watching $customPath")
                    } catch (e: Exception) {
                        Log.e(TAG, "Service: Error starting FileObserver", e)
                    }
                }
            }
        }
    }

    private fun unregisterObservers() {
        try {
            galleryImagesObserver?.let {
                contentResolver.unregisterContentObserver(it)
                galleryImagesObserver = null
            }
            galleryVideosObserver?.let {
                contentResolver.unregisterContentObserver(it)
                galleryVideosObserver = null
            }
            folderFileObserver?.let {
                it.stopWatching()
                folderFileObserver = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering observers", e)
        }
    }

    private fun handleNewMediaDetected() {
        serviceScope.launch {
            kotlinx.coroutines.delay(1500)
            val latest = getLatestMediaUriAndDate()
            if (latest != null) {
                val (latestUri, dateAddedSeconds) = latest
                val fileAddedTimeMs = dateAddedSeconds * 1000
                if (fileAddedTimeMs >= observerStartTime - 5000) {
                    val now = System.currentTimeMillis()
                    if (latestUri != lastObservedMediaUri && now - lastObservedMediaTime > 4000) {
                        // MD5 hash dedup: skip if same image content was already processed
                        try {
                            val inputStream = contentResolver.openInputStream(latestUri)
                            val imageBytes = inputStream?.use { it.readBytes() } ?: return@launch
                            val imageHash = java.security.MessageDigest.getInstance("MD5")
                                .digest(imageBytes).joinToString("") { "%02x".format(it) }
                            if (imageHash in processedImageHashes) {
                                Log.d(TAG, "Skipping duplicate image (hash=$imageHash)")
                                return@launch
                            }
                            processedImageHashes.add(imageHash)
                            if (processedImageHashes.size > 50) {
                                processedImageHashes.iterator().let { iter -> repeat(25) { iter.next(); iter.remove() } }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error checking image hash", e)
                        }

                        lastObservedMediaUri = latestUri
                        lastObservedMediaTime = now
                        imageQueue.offer(latestUri)
                        if (!isProcessing) processNextFromQueue()
                    }
                }
            }
        }
    }

    private fun processNextFromQueue() {
        val next = imageQueue.poll() ?: return
        isProcessing = true
        serviceScope.launch {
            try {
                processNewImageAndPostNotification(next)
            } finally {
                isProcessing = false
                processNextFromQueue()
            }
        }
    }

    private fun handleCustomFolderFile(file: File) {
        val name = file.name.lowercase()
        if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg") ||
            name.endsWith(".webp") || name.endsWith(".gif") || name.endsWith(".mp4")
        ) {
            serviceScope.launch {
                kotlinx.coroutines.delay(1200)
                if (file.lastModified() >= observerStartTime - 5000) {
                    val uri = Uri.fromFile(file)
                    val now = System.currentTimeMillis()
                    if (uri != lastObservedMediaUri && now - lastObservedMediaTime > 4000) {
                        lastObservedMediaUri = uri
                        lastObservedMediaTime = now
                        imageQueue.offer(uri)
                        if (!isProcessing) processNextFromQueue()
                    }
                }
            }
        }
    }

    private suspend fun processNewImageAndPostNotification(mediaUri: Uri) {
        try {
            val isEnabled = repository.getSettingValue("observe_media_enabled", "false").toBoolean()
            if (!isEnabled) return

            Log.d(TAG, "Processing new background image: $mediaUri")

            // 1. Get or create active ChatSession
            val sessions = repository.allSessions.first()
            val session = sessions.firstOrNull()
            val sessionId = if (session != null) {
                session.id
            } else {
                val appLang = repository.getSettingValue("app_language", "ru")
                val title = if (appLang == "ru") "Фоновый анализ" else "Background Analysis"
                val providers = repository.allProviders.first()
                val providerId = providers.find { it.isEnabled }?.id ?: "gemini_flash"
                repository.createNewSession(title, providerId)
            }

            val prompt = repository.getSettingValue("observe_media_prompt", "Analyze this new media file.")

            // 2. Insert user message with image attachment
            val userMsg = ChatMessage(
                sessionId = sessionId,
                role = "user",
                text = prompt,
                mediaUri = mediaUri.toString(),
                mediaType = "image",
                timestamp = System.currentTimeMillis()
            )
            repository.insertMessage(userMsg)

            // 3. Request AI Analysis
            val providers = repository.allProviders.first()
            val providerId = providers.find { it.isEnabled }?.id ?: "gemini_flash"
            val responseMsg = repository.sendChatMessageWithFallback(
                sessionId = sessionId,
                userMessageText = prompt,
                providerId = providerId,
                attachments = listOf(mediaUri),
                webSearchEnabled = false
            )

            // 4. Auto-post to WordPress
            val wpAutoPost = repository.getSettingValue("wp_auto_post", "false")
            if (wpAutoPost == "true") {
                repository.postChatToWordPress(sessionId)
            }

            // 5. Post Statusbar Notification
            NotificationHelper.postChatNotification(
                context = applicationContext,
                userRequest = prompt,
                aiResponse = responseMsg.text,
                mediaUriString = mediaUri.toString()
            )

            Log.d(TAG, "Successfully processed background image and posted notification!")

        } catch (e: Exception) {
            Log.e(TAG, "Error in processNewImageAndPostNotification", e)
        }
    }

    private fun getLatestMediaUriAndDate(): Pair<Uri, Long>? {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DATE_ADDED
        )
        val sortOrder = "${MediaStore.MediaColumns.DATE_ADDED} DESC"

        try {
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                    val id = cursor.getLong(idColumn)
                    val dateAdded = cursor.getLong(dateColumn)
                    val contentUri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
                    return Pair(contentUri, dateAdded)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying MediaStore", e)
        }
        return null
    }
}
