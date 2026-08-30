package com.example.wear.screen

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.view.WindowManager

class WearScreenManager(private val activity: Activity) {

    private var wakeLock: PowerManager.WakeLock? = null

    fun keepScreenOn() {
        // Standard Android flag
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Turn screen on when activity starts
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            activity.setShowWhenLocked(true)
            activity.setTurnScreenOn(true)
        }

        // Acquire wake lock for full screen + CPU
        val powerManager = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or
            PowerManager.ACQUIRE_CAUSES_WAKEUP or
            PowerManager.ON_AFTER_RELEASE,
            "OmniChat:WearWake"
        ).apply {
            acquire(30 * 60 * 1000L) // 30 minutes
        }
    }

    fun allowScreenOff() {
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        releaseWakeLock()
    }

    fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }

    fun activateScreen() {
        val powerManager = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
        val screenWakeLock = powerManager.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or
            PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "OmniChat:WearActivate"
        )
        screenWakeLock.acquire(5000) // 5 seconds to turn on screen

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            activity.setShowWhenLocked(true)
            activity.setTurnScreenOn(true)
        }
    }
}
