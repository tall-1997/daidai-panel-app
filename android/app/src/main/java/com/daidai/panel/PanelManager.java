package com.daidai.panel;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 面板管理器
 * 由于 Android SELinux 限制，无法直接执行 Go 二进制
 * 改为使用 WebView 直接加载本地 HTML 文件
 */
public class PanelManager {
    private static final String TAG = "PanelManager";
    private static volatile PanelManager instance;
    
    private final Context context;
    private volatile boolean isRunning = false;
    private int port = 5701;
    private final AtomicBoolean serverStarted = new AtomicBoolean(false);

    public static PanelManager getInstance(Context context) {
        if (instance == null) {
            synchronized (PanelManager.class) {
                if (instance == null) {
                    instance = new PanelManager(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private PanelManager(Context context) {
        this.context = context;
    }

    /**
     * 启动服务器（模拟）
     * 由于 Android 限制，使用 WebView 直接加载本地文件
     */
    public synchronized void startServer(String dataDir, String webDir, int port) {
        if (serverStarted.get()) {
            Log.w(TAG, "Server already started");
            return;
        }
        serverStarted.set(true);
        
        this.port = port;
        Log.d(TAG, "========================================");
        Log.d(TAG, "startServer (WebView mode)");
        Log.d(TAG, "Data: " + dataDir);
        Log.d(TAG, "Web: " + webDir);
        Log.d(TAG, "Port: " + port);

        // 创建数据目录
        new File(dataDir).mkdirs();
        new File(webDir).mkdirs();
        initDataDir(dataDir);
        
        // 标记为运行中（WebView 模式）
        isRunning = true;
        
        Log.d(TAG, "========================================");
        Log.d(TAG, "Server READY! (WebView mode)");
        Log.d(TAG, "========================================");
    }

    public void stopServer() {
        Log.d(TAG, "stopServer");
        isRunning = false;
        serverStarted.set(false);
    }

    public boolean isServerRunning() {
        return isRunning;
    }

    public int getServerPort() { return port; }
    public String getServerURL() { return "http://127.0.0.1:" + port; }
    
    /**
     * 获取 WebView 本地文件 URL
     */
    public String getLocalUrl() {
        String webDir = context.getFilesDir().getAbsolutePath() + "/web";
        File indexFile = new File(webDir, "index.html");
        if (indexFile.exists()) {
            return "file://" + indexFile.getAbsolutePath();
        }
        return null;
    }

    public void initDataDir(String dataDir) {
        new File(dataDir + "/scripts").mkdirs();
        new File(dataDir + "/logs").mkdirs();
        new File(dataDir + "/backups").mkdirs();
        new File(dataDir + "/deps").mkdirs();
    }

    public void initWebDir(String webDir) {
        new File(webDir).mkdirs();
    }

    public String getVersion() { return "0.0.1"; }
}
