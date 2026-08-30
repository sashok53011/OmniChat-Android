package com.example.data.api

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object DeutscheBahnApi {
    private const val TAG = "DeutscheBahnApi"
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // Public API endpoint for departures, no API key required
    suspend fun getDepartures(stationId: String): String = withContext(Dispatchers.IO) {
        val url = "https://v5.db.api.bahn.guru/departures/$stationId"
        
        try {
            val request = Request.Builder()
                .url(url)
                .addHeader("Accept", "application/json")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext "Error: DB API returned ${response.code}"
                }
                response.body?.string() ?: "Empty response"
            }
        } catch (e: Exception) {
            Log.e(TAG, "DB API call failed", e)
            "Error: ${e.message}"
        }
    }
}
