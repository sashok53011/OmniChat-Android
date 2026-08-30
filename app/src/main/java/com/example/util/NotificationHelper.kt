package com.example.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.text.Html
import android.text.Spanned
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object NotificationHelper {

    private const val TAG = "NotificationHelper"
    const val CHANNEL_ID = "omnichat_ai_responses_channel"
    private const val CHANNEL_NAME = "OmniChat AI — Ответы"
    private const val CHANNEL_DESC = "Уведомления с запросами и ответами ИИ, Markdown, математическими формулами и изображениями."

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Ensures the notification channel exists on Android 8.0 (API 26) or higher.
     */
    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, importance).apply {
                description = CHANNEL_DESC
                enableLights(true)
                enableVibration(true)
            }
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            notificationManager?.createNotificationChannel(channel)
        }
    }

    /**
     * Converts raw text containing Markdown formatting and LaTeX/Math formulas into a Spanned HTML text
     * for full rendering inside Android Statusbar Notifications.
     */
    fun markdownAndMathToHtml(text: String): Spanned {
        if (text.isBlank()) return Html.fromHtml("", Html.FROM_HTML_MODE_LEGACY)

        // 1. Convert LaTeX and Math formulas to Unicode symbols
        var processed = processMathSymbols(text)

        // Superscript and Subscript math conversions
        processed = processed
            .replace("^0", "⁰").replace("^1", "¹").replace("^2", "²").replace("^3", "³")
            .replace("^4", "⁴").replace("^5", "⁵").replace("^6", "⁶").replace("^7", "⁷")
            .replace("^8", "⁸").replace("^9", "⁹").replace("^x", "ˣ").replace("^y", "ʸ")
            .replace("^n", "ⁿ").replace("^+", "⁺").replace("^-", "⁻").replace("^=", "⁼")
            .replace("_0", "₀").replace("_1", "₁").replace("_2", "₂").replace("_3", "₃")
            .replace("_4", "₄").replace("_5", "₅").replace("_6", "₆").replace("_7", "₇")
            .replace("_8", "₈").replace("_9", "₉").replace("_x", "ₓ").replace("_a", "ₐ")
            .replace("_i", "ᵢ").replace("_j", "ⱼ").replace("_m", "ₘ").replace("_n", "ₙ")

        // 2. Escape HTML special characters
        processed = processed
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

        // 3. Convert Markdown code blocks ```lang ... ```
        processed = processed.replace(Regex("```(?:[a-zA-Z]*\n)?(.*?)```", RegexOption.DOT_MATCHES_ALL)) { match ->
            val code = match.groupValues[1].trim()
            "<br/><tt><font color='#00796B'><b>[Код]</b><br/>" + code.replace("\n", "<br/>") + "</font></tt><br/>"
        }

        // 4. Convert inline code `code`
        processed = processed.replace(Regex("`(.*?)`")) { match ->
            "<tt><font color='#C2185B'>" + match.groupValues[1] + "</font></tt>"
        }

        // 5. Convert Markdown Headers (# Header, ## Header, ### Header)
        processed = processed.replace(Regex("(?m)^#{1,6}\\s+(.*)$")) { match ->
            "<b><font color='#1565C0'>" + match.groupValues[1] + "</font></b>"
        }

        // 6. Convert Bold **text**
        processed = processed.replace(Regex("\\*\\*(.*?)\\*\\*")) { match ->
            "<b>" + match.groupValues[1] + "</b>"
        }

        // 7. Convert Italic *text*
        processed = processed.replace(Regex("\\*(.*?)\\*")) { match ->
            "<i>" + match.groupValues[1] + "</i>"
        }

        // 8. Convert bullet lists (- item, * item, • item)
        processed = processed.replace(Regex("(?m)^[\\-\\*•]\\s+(.*)$")) { match ->
            "• " + match.groupValues[1]
        }

        // 9. Convert blockquotes (> quote)
        processed = processed.replace(Regex("(?m)^&gt;\\s+(.*)$")) { match ->
            "<i>« " + match.groupValues[1] + " »</i>"
        }

        // 10. Preserve line breaks
        processed = processed.replace("\n", "<br/>")

        return Html.fromHtml(processed, Html.FROM_HTML_MODE_LEGACY)
    }

    /**
     * Converts LaTeX commands into human-readable Unicode mathematical symbols.
     */
    private fun processMathSymbols(text: String): String {
        // Block math $$ ... $$
        var result = text.replace(Regex("\\$\\$(.*?)\\$\\$", RegexOption.DOT_MATCHES_ALL)) { match ->
            val content = match.groupValues[1].trim()
            "\n📐 Математический блок:\n${content}\n"
        }

        // Inline math $ ... $
        result = result.replace(Regex("\\$(.*?)\\$")) { match ->
            " ${match.groupValues[1]} "
        }

        val mathCommands = mapOf(
            "\\alpha" to "α", "\\beta" to "β", "\\gamma" to "γ", "\\delta" to "δ",
            "\\epsilon" to "ε", "\\zeta" to "ζ", "\\eta" to "η", "\\theta" to "θ",
            "\\iota" to "ι", "\\kappa" to "κ", "\\lambda" to "λ", "\\mu" to "μ",
            "\\nu" to "ν", "\\xi" to "ξ", "\\pi" to "π", "\\rho" to "ρ",
            "\\sigma" to "σ", "\\tau" to "τ", "\\phi" to "φ", "\\chi" to "χ",
            "\\psi" to "ψ", "\\omega" to "ω", "\\sum" to "∑", "\\int" to "∫",
            "\\sqrt" to "√", "\\infty" to "∞", "\\neq" to "≠", "\\approx" to "≈",
            "\\le" to "≤", "\\ge" to "≥", "\\pm" to "±", "\\times" to "×",
            "\\div" to "÷", "\\partial" to "∂", "\\nabla" to "∇", "\\in" to "∈",
            "\\forall" to "∀", "\\exists" to "∃", "\\rightarrow" to "→",
            "\\leftrightarrow" to "↔", "\\cdot" to "·", "\\degree" to "°"
        )

        mathCommands.forEach { (cmd, replacement) ->
            result = result.replace(cmd, replacement)
        }

        // Handle \frac{a}{b} -> (a / b)
        result = result.replace(Regex("\\\\frac\\{(.*?)\\}\\{(.*?)\\}")) { match ->
            "(${match.groupValues[1]} / ${match.groupValues[2]})"
        }

        return result
    }

    /**
     * Attempts to extract or load a Bitmap from a URI, web URL, or Base64 string for notification big picture.
     */
    suspend fun loadBitmapFromUriOrUrl(context: Context, source: String?): Bitmap? = withContext(Dispatchers.IO) {
        if (source.isNullOrBlank()) return@withContext null
        try {
            when {
                source.startsWith("content://") || source.startsWith("file://") -> {
                    val uri = Uri.parse(source)
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        return@withContext scaleBitmapIfNeeded(BitmapFactory.decodeStream(stream))
                    }
                }
                source.startsWith("http://") || source.startsWith("https://") -> {
                    val request = Request.Builder().url(source).build()
                    httpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            response.body?.byteStream()?.use { stream ->
                                return@withContext scaleBitmapIfNeeded(BitmapFactory.decodeStream(stream))
                            }
                        }
                    }
                }
                source.startsWith("data:image/") -> {
                    val base64Data = source.substringAfter(",")
                    val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                    return@withContext scaleBitmapIfNeeded(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading bitmap from source: $source", e)
        }
        return@withContext null
    }

    /**
     * Extracts potential image URL from markdown or raw text.
     */
    fun findImageUrlInText(text: String): String? {
        // Markdown image ![alt](url)
        val markdownRegex = Regex("!\\[.*?\\]\\((https?://.*?\\.(?:png|jpg|jpeg|webp|gif).*?)\\)", RegexOption.IGNORE_CASE)
        val mdMatch = markdownRegex.find(text)
        if (mdMatch != null) return mdMatch.groupValues[1]

        // Direct URL regex
        val urlRegex = Regex("(https?://\\S+\\.(?:png|jpg|jpeg|webp|gif))", RegexOption.IGNORE_CASE)
        val urlMatch = urlRegex.find(text)
        if (urlMatch != null) return urlMatch.value

        return null
    }

    private fun scaleBitmapIfNeeded(bitmap: Bitmap?): Bitmap? {
        if (bitmap == null) return null
        val maxDimension = 1024
        if (bitmap.width <= maxDimension && bitmap.height <= maxDimension) return bitmap
        val ratio = Math.min(maxDimension.toFloat() / bitmap.width, maxDimension.toFloat() / bitmap.height)
        val newWidth = Math.round(ratio * bitmap.width)
        val newHeight = Math.round(ratio * bitmap.height)
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    /**
     * Posts a statusbar notification containing the user request and AI response with full markdown, math, and images.
     */
    suspend fun postChatNotification(
        context: Context,
        userRequest: String,
        aiResponse: String,
        mediaUriString: String? = null
    ) {
        withContext(Dispatchers.IO) {
            try {
                // Check notification permission for Android 13+
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        Log.w(TAG, "Notification permission POST_NOTIFICATIONS not granted.")
                        return@withContext
                    }
                }

                createNotificationChannel(context)

                // Try to resolve an image bitmap from mediaUriString or response text
                val imageSource = mediaUriString ?: findImageUrlInText(aiResponse) ?: findImageUrlInText(userRequest)
                val bitmap = loadBitmapFromUriOrUrl(context, imageSource)

                // Format text with Markdown & Math symbols
                val formattedUserRequest = markdownAndMathToHtml(userRequest)
                val formattedAiResponse = markdownAndMathToHtml(aiResponse)

                // Intent to open app when clicking notification
                val intent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                val pendingIntent = PendingIntent.getActivity(
                    context,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val reqShort = if (userRequest.length > 50) userRequest.take(47) + "..." else userRequest
                val title = if (reqShort.isNotBlank()) "OmniChat AI: $reqShort" else "OmniChat AI — Новый ответ"

                // Create full expandable content
                val fullHtmlContent = Html.fromHtml(
                    "<b>👤 Запрос:</b><br/>" +
                            Html.toHtml(formattedUserRequest, Html.TO_HTML_PARAGRAPH_LINES_CONSECUTIVE) +
                            "<br/><br/><b>🤖 Ответ ИИ:</b><br/>" +
                            Html.toHtml(formattedAiResponse, Html.TO_HTML_PARAGRAPH_LINES_CONSECUTIVE),
                    Html.FROM_HTML_MODE_LEGACY
                )

                val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(title)
                    .setContentText(formattedAiResponse)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setDefaults(NotificationCompat.DEFAULT_ALL)

                if (bitmap != null) {
                    val bigPictureStyle = NotificationCompat.BigPictureStyle()
                        .bigPicture(bitmap)
                        .bigLargeIcon(null as Bitmap?)
                        .setBigContentTitle("💬 OmniChat AI — Запрос и Ответ")
                        .setSummaryText(fullHtmlContent)
                    builder.setStyle(bigPictureStyle)
                    builder.setLargeIcon(bitmap)
                } else {
                    val bigTextStyle = NotificationCompat.BigTextStyle()
                        .setBigContentTitle("💬 OmniChat AI — Запрос и Ответ")
                        .bigText(fullHtmlContent)
                    builder.setStyle(bigTextStyle)
                }

                val notificationManager = NotificationManagerCompat.from(context)
                val notificationId = (System.currentTimeMillis() % 100000).toInt()
                notificationManager.notify(notificationId, builder.build())
                Log.d(TAG, "Successfully posted statusbar chat notification ID: $notificationId")

            } catch (e: Exception) {
                Log.e(TAG, "Error posting chat notification", e)
            }
        }
    }
}
