package com.daidai.panel

import android.content.Context
import android.util.Log
import mobile.DaidaiPanel
import mobile.Mobile
import java.io.File

class PanelManager(private val context: Context) {

    companion object {
        private const val TAG = "PanelManager"
    }

    private var panel: DaidaiPanel? = null
    private var isRunning = false
    private var dataDir: File? = null
    private var webDir: File? = null

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

            this.dataDir = dataDir

            // Web assets from bundle
            val webDir = File(context.filesDir, "web")
            if (!webDir.exists()) {
                copyAssetFolder("web", webDir)
            }
            this.webDir = webDir

            // Initialize gomobile binding
            panel = Mobile.newDaidaiPanel()
            panel.initialize(dataDir.absolutePath, webDir.absolutePath)

            // Start server in background
            Thread {
                try {
                    panel.start()
                    isRunning = true
                    Log.i(TAG, "Panel server started")
                    callback(true)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start panel", e)
                    callback(false)
                }
            }.start()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize", e)
            callback(false)
        }
    }

    fun stopService() {
        try {
            panel?.stop()
            isRunning = false
            Log.i(TAG, "Panel server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop panel", e)
        }
    }

    fun isRunning(): Boolean {
        return panel?.isRunning ?: false
    }

    fun getDataDir(): File? = dataDir
    fun getWebDir(): File? = webDir
    fun getServerURL(): String {
        return panel?.url ?: "http://127.0.0.1:5701"
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
                context.assets.open(assetPath).use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }
}
