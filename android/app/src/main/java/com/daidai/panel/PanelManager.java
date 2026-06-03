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
 * 面板管理器 - 负责管理Go后端服务
 */
public class PanelManager {
    private static final String TAG = "PanelManager";
    private static volatile PanelManager instance;
    
    private final Context context;
    private Process serverProcess;
    private volatile boolean isRunning = false;
    private volatile boolean processStarted = false;
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
     * 启动面板服务器
     */
    public synchronized void startServer(String dataDir, String webDir, int port) {
        if (serverStarted.get()) {
            Log.w(TAG, "Server already started, skipping");
            return;
        }
        serverStarted.set(true);
        
        this.port = port;
        Log.d(TAG, "========================================");
        Log.d(TAG, "startServer - 开始启动服务");
        Log.d(TAG, "Data dir: " + dataDir);
        Log.d(TAG, "Web dir: " + webDir);
        Log.d(TAG, "Port: " + port);

        // 创建目录
        new File(dataDir).mkdirs();
        new File(webDir).mkdirs();
        initDataDir(dataDir);

        // 复制二进制文件
        String binaryPath = copyBinaryToExecutableLocation();
        if (binaryPath == null) {
            Log.e(TAG, "ERROR: 复制二进制文件失败");
            return;
        }
        
        File binaryFile = new File(binaryPath);
        Log.d(TAG, "Binary path: " + binaryPath);
        Log.d(TAG, "Binary exists: " + binaryFile.exists());
        Log.d(TAG, "Binary size: " + binaryFile.length());
        Log.d(TAG, "Binary executable: " + binaryFile.canExecute());

        // 检查 web 目录
        File webDirFile = new File(webDir);
        File indexFile = new File(webDir, "index.html");
        Log.d(TAG, "Web dir exists: " + webDirFile.exists());
        Log.d(TAG, "index.html exists: " + indexFile.exists());

        // 启动子进程
        try {
            ProcessBuilder pb = new ProcessBuilder(
                binaryPath,
                "-data-dir", dataDir,
                "-web-dir", webDir,
                "-port", String.valueOf(port)
            );
            
            pb.directory(new File(dataDir));
            pb.redirectErrorStream(true);
            
            // 设置环境变量
            pb.environment().put("GODEBUG", "asyncpreemptoff=1");
            
            Log.d(TAG, "Starting process...");
            serverProcess = pb.start();
            processStarted = true;
            Log.d(TAG, "Process started successfully");

            // 读取进程输出
            Thread outputThread = new Thread(() -> {
                try {
                    BufferedReader reader = new BufferedReader(
                        new InputStreamReader(serverProcess.getInputStream()));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        Log.i(TAG, "[Server] " + line);
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Error reading output", e);
                }
                Log.d(TAG, "Output stream ended");
            }, "OutputReader");
            outputThread.setDaemon(true);
            outputThread.start();

            // HTTP 轮询检测
            Thread pollThread = new Thread(() -> {
                Log.d(TAG, "Starting HTTP poll...");
                
                for (int i = 1; i <= 60; i++) {
                    try {
                        Thread.sleep(1000);
                        
                        // 检查进程是否还活着
                        try {
                            int exitCode = serverProcess.exitValue();
                            Log.e(TAG, "Process exited with code: " + exitCode);
                            isRunning = false;
                            return;
                        } catch (IllegalThreadStateException e) {
                            // 进程还在运行
                        }
                        
                        // 检查 HTTP
                        if (checkHttpPort()) {
                            isRunning = true;
                            Log.d(TAG, "========================================");
                            Log.d(TAG, "Server is READY! (after " + i + "s)");
                            Log.d(TAG, "========================================");
                            return;
                        }
                        
                        if (i % 5 == 0) {
                            Log.d(TAG, "Waiting... " + i + "s");
                        }
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                
                Log.e(TAG, "TIMEOUT: 服务启动超时 (60s)");
            }, "HttpPoller");
            pollThread.setDaemon(true);
            pollThread.start();
            
        } catch (IOException e) {
            Log.e(TAG, "Failed to start process", e);
            e.printStackTrace();
        }
    }

    /**
     * 停止服务
     */
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
        processStarted = false;
        serverStarted.set(false);
    }

    /**
     * 检查服务是否运行中
     */
    public boolean isServerRunning() {
        if (serverProcess == null) {
            return false;
        }

        // 检查进程状态
        try {
            int exitCode = serverProcess.exitValue();
            Log.w(TAG, "Process exited with code: " + exitCode);
            isRunning = false;
            return false;
        } catch (IllegalThreadStateException e) {
            // 进程还在运行
        }

        // 如果已经确认运行中，直接返回
        if (isRunning) {
            return true;
        }

        // 尝试 HTTP 检测
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
            conn.setInstanceFollowRedirects(false);
            int code = conn.getResponseCode();
            Log.d(TAG, "HTTP check: port=" + port + " code=" + code);
            return code > 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    public int getServerPort() {
        return port;
    }

    public String getServerURL() {
        return "http://127.0.0.1:" + port;
    }

    public boolean isProcessStarted() {
        return processStarted;
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

    public String getVersion() {
        return "0.0.1";
    }

    private String copyBinaryToExecutableLocation() {
        String arch = getArch();
        String assetPath = "bin/daidai-server-" + arch;
        String targetPath = context.getFilesDir().getAbsolutePath() + "/bin/daidai-server";

        File targetFile = new File(targetPath);
        File targetDir = targetFile.getParentFile();

        if (!targetDir.exists()) {
            targetDir.mkdirs();
        }

        // 如果已存在且可执行，直接返回
        if (targetFile.exists() && targetFile.canExecute() && targetFile.length() > 1000000) {
            Log.d(TAG, "Binary already exists: " + targetPath + " (" + targetFile.length() + " bytes)");
            return targetPath;
        }

        // 从 assets 复制
        InputStream in = null;
        OutputStream out = null;
        try {
            Log.d(TAG, "Copying binary from: " + assetPath);
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

            Log.d(TAG, "Binary copied: " + total + " bytes");

            targetFile.setExecutable(true, false);
            return targetPath;
        } catch (IOException e) {
            Log.e(TAG, "Failed to copy binary", e);
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (IOException ignored) {}
            try { if (out != null) out.close(); } catch (IOException ignored) {}
        }
    }

    private String getArch() {
        String arch = android.os.Build.SUPPORTED_ABIS[0];
        if (arch.contains("arm64")) {
            return "arm64";
        } else if (arch.contains("arm")) {
            return "armv7";
        }
        return "arm64";
    }
}
