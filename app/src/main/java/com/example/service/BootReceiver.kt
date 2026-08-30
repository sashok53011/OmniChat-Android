package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.data.db.AppDatabase
import com.example.data.repository.AppRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Log.d("BootReceiver", "Boot or Package Replaced received: $action")
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val db = AppDatabase.getDatabase(context.applicationContext)
                    val repository = AppRepository(db.appDao(), context.applicationContext)
                    val isEnabled = repository.getSettingValue("observe_media_enabled", "false").toBoolean()
                    if (isEnabled) {
                        Log.d("BootReceiver", "Starting MediaObserverService from BootReceiver")
                        MediaObserverService.startService(context.applicationContext)
                    }
                } catch (e: Exception) {
                    Log.e("BootReceiver", "Error in BootReceiver", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
