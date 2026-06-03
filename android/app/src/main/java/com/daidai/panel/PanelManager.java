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

public class PanelManager {
    private static final String TAG = "PanelManager";
    private static volatile PanelManager instance;
    
    private final Context context;
    private Process serverProcess;
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

    public synchronized void startServer(String dataDir, String webDir, int port) {
        if (serverStarted.get()) {
            Log.w(TAG, "Server already started");
            return;
        }
        serverStarted.set(true);
        
        this.port = port;
        Log.d(TAG, "========================================");
        Log.d(TAG, "startServer");
        Log.d(TAG, "Data: " + dataDir);
        Log.d(TAG, "Web: " + webDir);
        Log.d(TAG, "Port: " + port);

        new File(dataDir).mkdirs();
        new File(webDir).mkdirs();
        initDataDir(dataDir);

        // 复制二进制到可执行位置
        String binaryPath = prepareBinary();
        if (binaryPath == null) {
            Log.e(TAG, "Failed to prepare binary");
            return;
        }
        
        Log.d(TAG, "Binary: " + binaryPath);
        File binaryFile = new File(binaryPath);
        Log.d(TAG, "Exists: " + binaryFile.exists());
        Log.d(TAG, "Size: " + binaryFile.length());

        // 尝试执行
        try {
            // 使用 sh -c 并设置 LD_LIBRARY_PATH
            String nativeLibDir = context.getApplicationInfo().nativeLibraryDir;
            String cmd = "chmod 755 " + binaryPath + " && " + binaryPath + 
                " -data-dir " + dataDir + 
                " -web-dir " + webDir + 
                " -port " + port;
            
            Log.d(TAG, "Executing: sh -c '" + cmd + "'");
            Log.d(TAG, "LD_LIBRARY_PATH: " + nativeLibDir);
            
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd);
            pb.directory(new File(dataDir));
            pb.redirectErrorStream(true);
            
            // 设置环境变量
            pb.environment().put("LD_LIBRARY_PATH", nativeLibDir);
            
            serverProcess = pb.start();
            Log.d(TAG, "Process started!");

            // 读取输出
            Thread outputThread = new Thread(() -> {
                try {
                    BufferedReader reader = new BufferedReader(
                        new InputStreamReader(serverProcess.getInputStream()));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        Log.i(TAG, "[Server] " + line);
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Read error", e);
                }
                Log.d(TAG, "Output ended");
            });
            outputThread.setDaemon(true);
            outputThread.start();

            // HTTP 轮询
            Thread pollThread = new Thread(() -> {
                Log.d(TAG, "HTTP poll started");
                for (int i = 1; i <= 60; i++) {
                    try {
                        Thread.sleep(1000);
                        
                        try {
                            int exitCode = serverProcess.exitValue();
                            Log.e(TAG, "Process exited: " + exitCode);
                            isRunning = false;
                            return;
                        } catch (IllegalThreadStateException e) {
                            // still running
                        }
                        
                        if (checkHttpPort()) {
                            isRunning = true;
                            Log.d(TAG, "========================================");
                            Log.d(TAG, "Server READY! (" + i + "s)");
                            Log.d(TAG, "========================================");
                            return;
                        }
                        
                        if (i % 5 == 0) Log.d(TAG, "Waiting... " + i + "s");
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                Log.e(TAG, "TIMEOUT (60s)");
            });
            pollThread.setDaemon(true);
            pollThread.start();
            
        } catch (IOException e) {
            Log.e(TAG, "Start failed", e);
        }
    }

    /**
     * 准备二进制文件
     */
    private String prepareBinary() {
        String arch = android.os.Build.SUPPORTED_ABIS[0].contains("arm64") ? "arm64" : "armv7";
        String assetPath = "bin/daidai-server-" + arch;
        
        // 优先使用 filesDir（虽然可能没有执行权限，但通过 sh -c 尝试）
        String targetPath = context.getFilesDir().getAbsolutePath() + "/bin/daidai-server";
        File targetFile = new File(targetPath);
        
        // 如果已存在且大小正确
        if (targetFile.exists() && targetFile.length() > 1000000) {
            Log.d(TAG, "Binary exists: " + targetPath);
            targetFile.setExecutable(true, false);
            return targetPath;
        }
        
        // 从 assets 复制
        targetFile.getParentFile().mkdirs();
        InputStream in = null;
        OutputStream out = null;
        try {
            Log.d(TAG, "Copying from assets: " + assetPath);
            in = context.getAssets().open(assetPath);
            out = new FileOutputStream(targetFile);
            
            byte[] buffer = new byte[8192];
            int read;
            long total = 0;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                total += read;
            }
            out.flush();
            
            Log.d(TAG, "Copied: " + total + " bytes");
            targetFile.setExecutable(true, false);
            
            return targetPath;
        } catch (IOException e) {
            Log.e(TAG, "Copy failed", e);
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (IOException ignored) {}
            try { if (out != null) out.close(); } catch (IOException ignored) {}
        }
    }

    public void stopServer() {
        Log.d(TAG, "stopServer");
        if (serverProcess != null) {
            serverProcess.destroy();
            try {
                serverProcess.waitFor();
            } catch (InterruptedException e) {
                Log.e(TAG, "Interrupted", e);
            }
            serverProcess = null;
        }
        isRunning = false;
        serverStarted.set(false);
    }

    public boolean isServerRunning() {
        if (serverProcess == null) return false;
        
        try {
            int exitCode = serverProcess.exitValue();
            Log.w(TAG, "Process exited: " + exitCode);
            isRunning = false;
            return false;
        } catch (IllegalThreadStateException e) {
            // still running
        }
        
        return isRunning || checkHttpPort();
    }

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
