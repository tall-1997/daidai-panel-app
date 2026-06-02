package com.daidai.panel

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import java.io.File
import java.io.FileOutputStream

class PanelManager(private val context: Context) : DefaultLifecycleObserver {

    companion object {
        private const val TAG = "PanelManager"
        private const val BINARY_NAME = "daidai-panel"
        private const val WEB_DIR_NAME = "web"
    }

    private var process: Process? = null
    private var isRunning = false
    private var onDataDir: File? = null
    private var onWebDir: File? = null

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    fun startService(callback: (Boolean) -> Unit) {
        try {
            // Prepare directories
            val dataDir = File(context.filesDir, "Dumb-Panel")
            dataDir.mkdirs()

            val dbDir = File(dataDir, "db")
            dbDir.mkdirs()

            val scriptsDir = File(dataDir, "scripts")
            scriptsDir.mkdirs()

            val logDir = File(dataDir, "log")
            logDir.mkdirs()

            onDataDir = dataDir

            // Copy binary from assets
            val binaryFile = File(context.filesDir, BINARY_NAME)
            if (!binaryFile.exists()) {
                copyAsset(BINARY_NAME, binaryFile)
                binaryFile.setExecutable(true)
            }

            // Copy web assets
            val webDir = File(context.filesDir, WEB_DIR_NAME)
            if (!webDir.exists()) {
                copyAssetFolder(WEB_DIR_NAME, webDir)
            }
            onWebDir = webDir

            // Copy config template
            val configFile = File(dataDir, "config.yaml")
            if (!configFile.exists()) {
                val configContent = """
                    server:
                      port: 5701
                      mode: release
                      web_dir: ${webDir.absolutePath}
                    database:
                      path: ${File(dbDir, "panel.db").absolutePath}
                    data:
                      dir: ${dataDir.absolutePath}
                      scripts_dir: ${scriptsDir.absolutePath}
                      log_dir: ${logDir.absolutePath}
                    cors:
                      origins:
                        - "*"
                """.trimIndent()
                configFile.writeText(configContent)
            }

            // Start service
            val intent = Intent(context, PanelService::class.java).apply {
                putExtra("data_dir", dataDir.absolutePath)
                putExtra("web_dir", webDir.absolutePath)
                putExtra("binary_path", binaryFile.absolutePath)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }

            isRunning = true
            callback(true)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start service", e)
            callback(false)
        }
    }

    fun stopService() {
        try {
            context.stopService(Intent(context, PanelService::class.java))
            isRunning = false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop service", e)
        }
    }

    fun isRunning(): Boolean {
        return isRunning
    }

    fun getDataDir(): File? = onDataDir
    fun getWebDir(): File? = onWebDir

    private fun copyAsset(assetName: String, destFile: File) {
        context.assets.open(assetName).use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun copyAssetFolder(assetFolder: String, destFolder: File) {
        destFolder.mkdirs()
        val assets = context.assets.list(assetFolder) ?: return

        for (asset in assets) {
            val assetPath = "$assetFolder/$asset"
            val destFile = File(destFolder, asset)

            val subAssets = context.assets.list(assetPath)
            if (subAssets != null && subAssets.isNotEmpty()) {
                copyAssetFolder(assetPath, destFile)
            } else {
                copyAsset(assetPath, destFile)
            }
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        super.onStart()
        Log.d(TAG, "App in foreground")
    }

    override fun onStop(owner: LifecycleOwner) {
        super.onStop()
        Log.d(TAG, "App in background")
    }

    override fun onDestroy(owner: LifecycleOwner) {
        super.onDestroy()
        Log.d(TAG, "App destroyed")
    }
}
