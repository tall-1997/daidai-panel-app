package com.daidai.panel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.BufferedReader
import java.io.InputStreamReader

class PanelService : Service() {

    companion object {
        private const val TAG = "PanelService"
        private const val CHANNEL_ID = "daidai_panel_service"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP = "com.daidai.panel.STOP"
    }

    private var process: Process? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var dataDir: String? = null
    private var webDir: String? = null
    private var binaryPath: String? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopPanel()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                dataDir = intent?.getStringExtra("data_dir")
                webDir = intent?.getStringExtra("web_dir")
                binaryPath = intent?.getStringExtra("binary_path")

                startForeground(NOTIFICATION_ID, createNotification())
                acquireWakeLock()
                startPanel()
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPanel()
        releaseWakeLock()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "呆呆面板服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持呆呆面板在后台运行"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, PanelService::class.java).apply {
                action = ACTION_STOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("呆呆面板")
            .setContentText("面板正在运行")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_stop, "停止", stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "daidai:panel_service"
        ).apply {
            acquire(10 * 60 * 1000L) // 10 minutes
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }

    private fun startPanel() {
        try {
            val binary = binaryPath ?: run {
                Log.e(TAG, "Binary path not set")
                return
            }

            val configFile = "$dataDir/config.yaml"

            val processBuilder = ProcessBuilder(
                binary,
                "-config", configFile
            )

            processBuilder.environment().apply {
                put("DAIDAI_CONFIG", configFile)
                put("DAIDAI_DATA_DIR", dataDir)
                put("DAIDAI_WEB_DIR", webDir)
            }

            processBuilder.redirectErrorStream(true)

            process = processBuilder.start()

            // Log output
            Thread {
                try {
                    val reader = BufferedReader(InputStreamReader(process!!.inputStream))
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        Log.d(TAG, "Panel: $line")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading process output", e)
                }
            }.start()

            // Monitor process
            Thread {
                try {
                    val exitCode = process!!.waitFor()
                    Log.w(TAG, "Panel process exited with code: $exitCode")

                    // Restart if crashed
                    if (exitCode != 0) {
                        Thread.sleep(2000)
                        startPanel()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error monitoring process", e)
                }
            }.start()

            Log.i(TAG, "Panel started successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start panel", e)
        }
    }

    private fun stopPanel() {
        try {
            process?.let {
                if (it.isAlive) {
                    it.destroy()
                    it.waitFor()
                }
            }
            process = null
            Log.i(TAG, "Panel stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop panel", e)
        }
    }
}
