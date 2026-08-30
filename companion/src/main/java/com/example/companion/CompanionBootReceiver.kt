package com.example.companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class CompanionBootReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "CompanionBootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {

            Log.d(TAG, "Boot received, starting companion service")

            val pendingResult = goAsync()
            Thread {
                try {
                    CompanionForegroundService.startService(context)
                    Log.d(TAG, "Companion service started")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start companion service", e)
                } finally {
                    pendingResult.finish()
                }
            }.start()
        }
    }
}
