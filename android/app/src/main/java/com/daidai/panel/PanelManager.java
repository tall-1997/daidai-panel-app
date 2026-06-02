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

/**
 * 面板管理器，负责管理Go后端服务
 * 通过子进程方式启动Go二进制文件，通过HTTP检测服务状态
 * 使用单例模式确保所有组件共享同一实例
 */
public class PanelManager {
    private static final String TAG = "PanelManager";
    private static volatile PanelManager instance;
    
    private final Context context;
    private Process serverProcess;
    private boolean isRunning = false;
    private int port = 5701;

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
    public void startServer(String dataDir, String webDir, int port) {
        this.port = port;
        Log.d(TAG, "startServer called");
        Log.d(TAG, "Data dir: " + dataDir);
        Log.d(TAG, "Web dir: " + webDir);
        Log.d(TAG, "Port: " + port);

        // 确保目录存在
        new File(dataDir).mkdirs();
        new File(webDir).mkdirs();

        // 复制二进制文件到可执行位置
        String binaryPath = copyBinaryToExecutableLocation();
        if (binaryPath == null) {
            Log.e(TAG, "Failed to copy binary");
            return;
        }

        // 启动子进程
        try {
            ProcessBuilder pb = new ProcessBuilder(
                binaryPath,
                "-data-dir", dataDir,
                "-web-dir", webDir,
                "-port", String.valueOf(port)
            );
            pb.redirectErrorStream(true);
            serverProcess = pb.start();

            // 读取进程输出（避免阻塞）
            new Thread(() -> {
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
            }).start();

            isRunning = true;
            Log.d(TAG, "Server process started");
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
    }

    /**
     * 检查服务器是否运行中
     * 通过HTTP请求检测服务状态
     */
    public boolean isServerRunning() {
        if (!isRunning || serverProcess == null) {
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
            return checkHttpPort();
        }
    }

    /**
     * 通过HTTP请求检查端口是否可访问
     */
    private boolean checkHttpPort() {
        try {
            URL url = new URL("http://127.0.0.1:" + port);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(1000);
            conn.setReadTimeout(1000);
            conn.setRequestMethod("GET");
            int responseCode = conn.getResponseCode();
            conn.disconnect();
            return responseCode > 0;
        } catch (Exception e) {
            return false;
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
        try {
            InputStream in = context.getAssets().open(assetPath);
            OutputStream out = new FileOutputStream(targetFile);

            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }

            in.close();
            out.close();

            // 设置可执行权限
            targetFile.setExecutable(true, false);

            Log.d(TAG, "Binary copied to: " + targetPath);
            return targetPath;
        } catch (IOException e) {
            Log.e(TAG, "Failed to copy binary from assets: " + assetPath, e);
            return null;
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
