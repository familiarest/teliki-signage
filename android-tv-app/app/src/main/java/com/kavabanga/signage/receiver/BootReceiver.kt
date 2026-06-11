package com.kavabanga.signage.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.kavabanga.signage.ui.setup.SetupActivity
import com.kavabanga.signage.ui.player.PlayerActivity
import com.kavabanga.signage.data.PrefsManager

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i(TAG, "Boot completed — launching signage app")
            try {
                val targetClass = if (PrefsManager.getInstance(context).isConfigured()) {
                    PlayerActivity::class.java
                } else {
                    SetupActivity::class.java
                }
                val launchIntent = Intent(context, targetClass).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(launchIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch after boot", e)
            }
        }
    }
}
