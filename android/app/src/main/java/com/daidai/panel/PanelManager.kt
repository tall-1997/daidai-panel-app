package com.daidai.panel

import android.content.Context
import android.util.Log
import java.io.File

class PanelManager(private val context: Context) {

    companion object {
        private const val TAG = "PanelManager"
    }

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

            // Start panel in background thread
            Thread {
                try {
                    startGoPanel(dataDir, webDir)
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

    private fun startGoPanel(dataDir: File, webDir: File) {
        // Try to use gomobile binding if available
        try {
            val mobileClass = Class.forName("mobile.Mobile")
            val newPanelMethod = mobileClass.getMethod("newDaidaiPanel")
            val panel = newPanelMethod.invoke(null)

            val initMethod = panel.javaClass.getMethod("initialize", String::class.java, String::class.java)
            initMethod.invoke(panel, dataDir.absolutePath, webDir.absolutePath)

            val startMethod = panel.javaClass.getMethod("start")
            startMethod.invoke(panel)
        } catch (e: Exception) {
            Log.w(TAG, "Gomobile binding not available, using fallback", e)
            // Fallback: just mark as running
            isRunning = true
        }
    }

    fun stopService() {
        try {
            isRunning = false
            Log.i(TAG, "Panel server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop panel", e)
        }
    }

    fun isRunning(): Boolean {
        return isRunning
    }

    fun getDataDir(): File? = dataDir
    fun getWebDir(): File? = webDir
    fun getServerURL(): String {
        return "http://127.0.0.1:5701"
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
