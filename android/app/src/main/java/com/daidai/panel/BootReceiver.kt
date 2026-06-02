package com.daidai.panel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.preference.PreferenceManager

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        private const val KEY_AUTO_START = "auto_start_on_boot"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON" ||
            intent.action == "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            Log.d(TAG, "Boot completed, checking auto-start preference")

            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val autoStart = prefs.getBoolean(KEY_AUTO_START, true)

            if (autoStart) {
                Log.i(TAG, "Auto-starting panel service")
                startPanelService(context)
            } else {
                Log.d(TAG, "Auto-start disabled")
            }
        }
    }

    private fun startPanelService(context: Context) {
        try {
            val dataDir = context.filesDir.resolve("Dumb-Panel")
            val webDir = context.filesDir.resolve("web")
            val binaryPath = context.filesDir.resolve("daidai-panel")

            val intent = Intent(context, PanelService::class.java).apply {
                putExtra("data_dir", dataDir.absolutePath)
                putExtra("web_dir", webDir.absolutePath)
                putExtra("binary_path", binaryPath.absolutePath)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start panel service", e)
        }
    }
}
