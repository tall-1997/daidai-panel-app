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
 * 面板管理器，负责管理Go后端服务
 * 通过子进程方式启动Go二进制文件，通过HTTP轮询检测服务状态
 * 使用单例模式确保所有组件共享同一实例
 */
public class PanelManager {
    private static final String TAG = "PanelManager";
    private static volatile PanelManager instance;
    
    private final Context context;
    private Process serverProcess;
    private volatile boolean isRunning = false;
    private int port = 5701;
    private final AtomicBoolean serverStarted = new AtomicBoolean(false);
    private Thread outputReaderThread;

    /**
     * 获取单例实例
     */
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
     * @param dataDir 数据目录
     * @param webDir 前端资源目录
     * @param port 监听端口
     */
    public synchronized void startServer(String dataDir, String webDir, int port) {
        if (serverStarted.get()) {
            Log.d(TAG, "Server already started or starting, skipping");
            return;
        }
        serverStarted.set(true);
        
        this.port = port;
        Log.d(TAG, "=== startServer called ===");
        Log.d(TAG, "Data dir: " + dataDir);
        Log.d(TAG, "Web dir: " + webDir);
        Log.d(TAG, "Port: " + port);

        // 确保目录存在
        new File(dataDir).mkdirs();
        new File(webDir).mkdirs();

        // 初始化数据目录结构
        initDataDir(dataDir);

        // 复制二进制文件到可执行位置
        String binaryPath = copyBinaryToExecutableLocation();
        if (binaryPath == null) {
            Log.e(TAG, "Failed to copy binary");
            return;
        }
        
        Log.d(TAG, "Binary path: " + binaryPath);
        Log.d(TAG, "Binary exists: " + new File(binaryPath).exists());
        Log.d(TAG, "Binary executable: " + new File(binaryPath).canExecute());

        // 启动子进程
        try {
            ProcessBuilder pb = new ProcessBuilder(
                binaryPath,
                "-data-dir", dataDir,
                "-web-dir", webDir,
                "-port", String.valueOf(port)
            );
            
            // 设置工作目录
            pb.directory(new File(dataDir));
            
            // 重定向错误流到标准输出
            pb.redirectErrorStream(true);
            
            Log.d(TAG, "Starting process...");
            serverProcess = pb.start();
            Log.d(TAG, "Process started successfully");

            // 读取进程输出
            outputReaderThread = new Thread(() -> {
                try {
                    BufferedReader reader = new BufferedReader(
                        new InputStreamReader(serverProcess.getInputStream()));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        Log.d(TAG, "[Server] " + line);
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Error reading process output", e);
                }
                
                Log.d(TAG, "Server process output stream ended");
            }, "ServerOutputReader");
            outputReaderThread.setDaemon(true);
            outputReaderThread.start();

            // 启动HTTP轮询线程
            new Thread(() -> {
                Log.d(TAG, "Starting HTTP poll...");
                int maxRetries = 60; // 最多等待60秒
                int retryCount = 0;
                
                while (retryCount < maxRetries) {
                    try {
                        Thread.sleep(1000);
                        retryCount++;
                        
                        // 检查进程是否还活着
                        try {
                            serverProcess.exitValue();
                            Log.e(TAG, "Process exited with code: " + serverProcess.exitValue());
                            isRunning = false;
                            return;
                        } catch (IllegalThreadStateException e) {
                            // 进程还在运行
                        }
                        
                        // 检查HTTP端口
                        if (checkHttpPort()) {
                            isRunning = true;
                            Log.d(TAG, "=== Server is ready! (after " + retryCount + "s) ===");
                            return;
                        }
                        
                        Log.d(TAG, "Waiting for server... " + retryCount + "s");
                    } catch (InterruptedException e) {
                        Log.e(TAG, "Poll thread interrupted", e);
                        return;
                    }
                }
                
                Log.e(TAG, "Server startup timeout after " + maxRetries + "s");
            }, "ServerHttpPoller").start();
            
        } catch (IOException e) {
            Log.e(TAG, "Failed to start server process", e);
        }
    }

    /**
     * 停止面板服务器
     */
    public void stopServer() {
        Log.d(TAG, "stopServer called");
        if (serverProcess != null) {
            serverProcess.destroy();
            try {
                serverProcess.waitFor();
            } catch (InterruptedException e) {
                Log.e(TAG, "Interrupted while waiting for server to stop", e);
            }
            serverProcess = null;
        }
        isRunning = false;
        serverStarted.set(false);
    }

    /**
     * 检查服务器是否运行中
     */
    public boolean isServerRunning() {
        if (serverProcess == null) {
            return false;
        }

        // 检查进程是否还活着
        try {
            serverProcess.exitValue();
            // 进程已退出
            isRunning = false;
            return false;
        } catch (IllegalThreadStateException e) {
            // 进程还在运行，检查HTTP端口
            return isRunning || checkHttpPort();
        }
    }

    /**
     * 通过HTTP请求检查端口是否可访问
     */
    private boolean checkHttpPort() {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("http://127.0.0.1:" + port);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(1000);
            conn.setReadTimeout(1000);
            conn.setRequestMethod("GET");
            conn.setUseCaches(false);
            int responseCode = conn.getResponseCode();
            Log.d(TAG, "HTTP check: port=" + port + ", code=" + responseCode);
            return responseCode > 0;
        } catch (Exception e) {
            // 不打印日志，太频繁
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * 获取服务器端口
     */
    public int getServerPort() {
        return port;
    }

    /**
     * 获取服务器URL
     */
    public String getServerURL() {
        return "http://127.0.0.1:" + port;
    }

    /**
     * 初始化数据目录
     */
    public void initDataDir(String dataDir) {
        new File(dataDir + "/scripts").mkdirs();
        new File(dataDir + "/logs").mkdirs();
        new File(dataDir + "/backups").mkdirs();
        new File(dataDir + "/deps").mkdirs();
    }

    /**
     * 初始化Web目录
     */
    public void initWebDir(String webDir) {
        new File(webDir).mkdirs();
    }

    /**
     * 获取版本号
     */
    public String getVersion() {
        return "0.0.1";
    }

    /**
     * 从assets复制二进制文件到可执行位置
     * @return 二进制文件路径，失败返回null
     */
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
        if (targetFile.exists() && targetFile.canExecute()) {
            Log.d(TAG, "Binary already exists: " + targetPath);
            return targetPath;
        }

        // 从assets复制
        InputStream in = null;
        OutputStream out = null;
        try {
            Log.d(TAG, "Copying binary from assets: " + assetPath);
            in = context.getAssets().open(assetPath);
            out = new FileOutputStream(targetFile);

            byte[] buffer = new byte[8192];
            int read;
            long totalRead = 0;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                totalRead += read;
            }
            out.flush();

            Log.d(TAG, "Binary copied, size: " + totalRead + " bytes");

            // 设置可执行权限
            boolean executable = targetFile.setExecutable(true, false);
            Log.d(TAG, "Set executable: " + executable);

            Log.d(TAG, "Binary copied to: " + targetPath);
            return targetPath;
        } catch (IOException e) {
            Log.e(TAG, "Failed to copy binary from assets: " + assetPath, e);
            return null;
        } finally {
            try {
                if (in != null) in.close();
                if (out != null) out.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing streams", e);
            }
        }
    }

    /**
     * 获取当前设备架构
     */
    private String getArch() {
        String arch = android.os.Build.SUPPORTED_ABIS[0];
        if (arch.contains("arm64")) {
            return "arm64";
        } else if (arch.contains("arm")) {
            return "armv7";
        }
        return "arm64"; // 默认arm64
    }
}
