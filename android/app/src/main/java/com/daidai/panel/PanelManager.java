package com.daidai.panel;

import android.content.Context;
import android.util.Log;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;

import mobile.DaidaiPanel;

/**
 * 面板管理器 - 使用 gomobile 调用 Go 后端
 */
public class PanelManager {
    private static final String TAG = "PanelManager";
    private static volatile PanelManager instance;
    
    private final Context context;
    private volatile boolean isRunning = false;
    private int port = 5701;
    private final AtomicBoolean serverStarted = new AtomicBoolean(false);
    private DaidaiPanel panel;

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
        this.panel = new DaidaiPanel();
    }

    /**
     * 启动面板服务器
     */
    public synchronized void startServer(String dataDir, String webDir, int port) {
        if (serverStarted.get()) {
            Log.w(TAG, "Server already started");
            return;
        }
        serverStarted.set(true);
        
        this.port = port;
        Log.d(TAG, "========================================");
        Log.d(TAG, "startServer (gomobile)");
        Log.d(TAG, "Data: " + dataDir);
        Log.d(TAG, "Web: " + webDir);
        Log.d(TAG, "Port: " + port);

        // 在后台线程启动服务器
        new Thread(() -> {
            try {
                Log.d(TAG, "Calling Go StartServer...");
                String result = panel.startServer(dataDir, webDir, port);
                Log.d(TAG, "StartServer result: " + result);
                
                if (result.contains("\"success\":true")) {
                    isRunning = true;
                    Log.d(TAG, "========================================");
                    Log.d(TAG, "Server READY!");
                    Log.d(TAG, "========================================");
                } else {
                    Log.e(TAG, "Server failed to start: " + result);
                }
            } catch (Exception e) {
                Log.e(TAG, "StartServer exception", e);
            }
        }, "GoServerStarter").start();

        // HTTP 轮询检测
        new Thread(() -> {
            Log.d(TAG, "HTTP poll started");
            for (int i = 1; i <= 60; i++) {
                try {
                    Thread.sleep(1000);
                    
                    if (checkHttpPort()) {
                        isRunning = true;
                        Log.d(TAG, "Server READY via HTTP! (" + i + "s)");
                        return;
                    }
                    
                    if (i % 5 == 0) Log.d(TAG, "Waiting... " + i + "s");
                } catch (InterruptedException e) {
                    return;
                }
            }
            Log.e(TAG, "TIMEOUT (60s)");
        }, "HttpPoller").start();
    }

    /**
     * 停止服务器
     */
    public void stopServer() {
        Log.d(TAG, "stopServer");
        try {
            String result = panel.stopServer();
            Log.d(TAG, "StopServer result: " + result);
        } catch (Exception e) {
            Log.e(TAG, "StopServer exception", e);
        }
        isRunning = false;
        serverStarted.set(false);
    }

    /**
     * 检查服务器是否运行中
     */
    public boolean isServerRunning() {
        if (isRunning) {
            return true;
        }
        
        // 尝试通过 Go 代码检查
        try {
            boolean running = panel.isServerRunning();
            if (running) {
                isRunning = true;
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "isServerRunning exception", e);
        }
        
        return checkHttpPort();
    }

    /**
     * HTTP 端口检测
     */
    private boolean checkHttpPort() {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("http://127.0.0.1:" + port);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            conn.setRequestMethod("GET");
            conn.setUseCaches(false);
            int code = conn.getResponseCode();
            return code > 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public int getServerPort() { return port; }
    public String getServerURL() { return "http://127.0.0.1:" + port; }

    public void initDataDir(String dataDir) {
        try {
            String result = panel.initDataDir(dataDir);
            Log.d(TAG, "initDataDir result: " + result);
        } catch (Exception e) {
            Log.e(TAG, "initDataDir exception", e);
        }
    }

    public void initWebDir(String webDir) {
        // Go 代码会自动处理
    }

    public String getVersion() { return "0.0.1"; }
    
    /**
     * 通知 Alpine 环境已就绪
     */
    public void setAlpineReady(String dataDir, String prootBin) {
        try {
            String result = panel.setAlpineReady(dataDir, prootBin);
            Log.d(TAG, "setAlpineReady result: " + result);
        } catch (Exception e) {
            Log.e(TAG, "setAlpineReady failed", e);
        }
    }
}
