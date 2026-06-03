package com.daidai.panel;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class SplashActivity extends AppCompatActivity {
    private static final String TAG = "SplashActivity";
    
    private TextView statusText;
    private TextView pythonStatus;
    private TextView nodeStatus;
    private LinearLayout downloadArea;
    private TextView downloadStatus;
    private ProgressBar downloadProgress;
    private TextView downloadPercent;
    private Button startButton;
    private Button downloadButton;
    private Button importButton;
    private Button skipButton;
    
    private Handler handler;
    private boolean pythonInstalled = false;
    private boolean nodeInstalled = false;
    private boolean isDownloading = false;
    
    // 文件选择器
    private ActivityResultLauncher<Intent> filePickerLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);
        
        handler = new Handler(Looper.getMainLooper());
        
        // 初始化文件选择器
        filePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        handleImportedFile(uri);
                    }
                }
            }
        );
        
        initViews();
        checkDependencies();
    }

    private void initViews() {
        statusText = findViewById(R.id.status_text);
        pythonStatus = findViewById(R.id.python_status);
        nodeStatus = findViewById(R.id.node_status);
        downloadArea = findViewById(R.id.download_area);
        downloadStatus = findViewById(R.id.download_status);
        downloadProgress = findViewById(R.id.download_progress);
        downloadPercent = findViewById(R.id.download_percent);
        startButton = findViewById(R.id.start_button);
        downloadButton = findViewById(R.id.download_button);
        importButton = findViewById(R.id.import_button);
        skipButton = findViewById(R.id.skip_button);
        
        startButton.setOnClickListener(v -> startMainActivity());
        downloadButton.setOnClickListener(v -> downloadRuntimes());
        importButton.setOnClickListener(v -> openFilePicker());
        skipButton.setOnClickListener(v -> startMainActivity());
    }

    private void checkDependencies() {
        statusText.setText("正在检查环境...");
        
        new Thread(() -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                // ignore
            }
            
            pythonInstalled = checkPythonInstalled();
            handler.post(() -> updatePythonStatus(pythonInstalled));
            
            nodeInstalled = checkNodeInstalled();
            handler.post(() -> updateNodeStatus(nodeInstalled));
            
            handler.post(() -> {
                if (pythonInstalled && nodeInstalled) {
                    showReady();
                } else {
                    showInstallNeeded();
                }
            });
        }).start();
    }

    private boolean checkPythonInstalled() {
        String dataDir = getFilesDir().getAbsolutePath() + "/Dumb-Panel";
        String pythonBin = dataDir + "/deps/bin/python/bin/python3.12";
        File file = new File(pythonBin);
        boolean exists = file.exists();
        Log.d(TAG, "Python check: " + exists + " (" + pythonBin + ")");
        return exists;
    }

    private boolean checkNodeInstalled() {
        String dataDir = getFilesDir().getAbsolutePath() + "/Dumb-Panel";
        String nodeBin = dataDir + "/deps/bin/node/bin/node";
        File file = new File(nodeBin);
        boolean exists = file.exists();
        Log.d(TAG, "Node check: " + exists + " (" + nodeBin + ")");
        return exists;
    }

    private void updatePythonStatus(boolean installed) {
        if (installed) {
            pythonStatus.setText("已安装");
            pythonStatus.setTextColor(0xFF4CAF50);
        } else {
            pythonStatus.setText("未安装");
            pythonStatus.setTextColor(0xFFFF9800);
        }
    }

    private void updateNodeStatus(boolean installed) {
        if (installed) {
            nodeStatus.setText("已安装");
            nodeStatus.setTextColor(0xFF4CAF50);
        } else {
            nodeStatus.setText("未安装");
            nodeStatus.setTextColor(0xFFFF9800);
        }
    }

    private void showReady() {
        statusText.setText("环境检查完成");
        startButton.setVisibility(View.VISIBLE);
        downloadButton.setVisibility(View.GONE);
        importButton.setVisibility(View.GONE);
        skipButton.setVisibility(View.GONE);
    }

    private void showInstallNeeded() {
        statusText.setText("需要安装运行时");
        startButton.setVisibility(View.GONE);
        downloadButton.setVisibility(View.VISIBLE);
        importButton.setVisibility(View.VISIBLE);
        skipButton.setVisibility(View.VISIBLE);
    }

    private void downloadRuntimes() {
        if (isDownloading) return;
        isDownloading = true;
        
        downloadButton.setEnabled(false);
        downloadButton.setText("下载中...");
        downloadArea.setVisibility(View.VISIBLE);
        
        new Thread(() -> {
            if (!pythonInstalled) {
                downloadRuntime("python");
            }
            
            if (!nodeInstalled) {
                downloadRuntime("node");
            }
            
            pythonInstalled = checkPythonInstalled();
            nodeInstalled = checkNodeInstalled();
            
            handler.post(() -> {
                isDownloading = false;
                downloadArea.setVisibility(View.GONE);
                
                updatePythonStatus(pythonInstalled);
                updateNodeStatus(nodeInstalled);
                
                if (pythonInstalled && nodeInstalled) {
                    showReady();
                    statusText.setText("下载完成！请点击启动面板");
                } else {
                    downloadButton.setEnabled(true);
                    downloadButton.setText("重试下载");
                    statusText.setText("下载失败，请重试或本地导入");
                }
            });
        }).start();
    }

    private void downloadRuntime(String name) {
        Log.d(TAG, "Downloading " + name + "...");
        handler.post(() -> {
            downloadStatus.setText("正在下载 " + name + "...");
            downloadProgress.setProgress(0);
            downloadPercent.setText("0%");
        });
        
        try {
            String token = MainActivity.authToken;
            if (token == null) {
                Log.e(TAG, "Auth token is null");
                return;
            }
            
            String url = "http://127.0.0.1:5701/api/v1/android-runtime/install";
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(300000);
            
            String body = "{\"name\":\"" + name + "\"}";
            conn.getOutputStream().write(body.getBytes());
            
            int responseCode = conn.getResponseCode();
            Log.d(TAG, "Install API response: " + responseCode);
            
            if (responseCode == 200) {
                InputStream is = conn.getInputStream();
                byte[] buffer = new byte[1024];
                int bytesRead;
                long totalRead = 0;
                
                while ((bytesRead = is.read(buffer)) != -1) {
                    totalRead += bytesRead;
                    // 解析 SSE 数据
                    String data = new String(buffer, 0, bytesRead);
                    if (data.contains("data: ")) {
                        String[] lines = data.split("\n");
                        for (String line : lines) {
                            if (line.startsWith("data: ")) {
                                String msg = line.substring(6).trim();
                                Log.d(TAG, "SSE: " + msg);
                                handler.post(() -> downloadStatus.setText(msg));
                            }
                        }
                    }
                }
                is.close();
            }
            
            conn.disconnect();
            
        } catch (Exception e) {
            Log.e(TAG, "Download failed", e);
            handler.post(() -> Toast.makeText(this, "下载失败: " + e.getMessage(), Toast.LENGTH_LONG).show());
        }
    }

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        filePickerLauncher.launch(Intent.createChooser(intent, "选择运行时文件"));
    }

    private void handleImportedFile(Uri uri) {
        Toast.makeText(this, "正在导入文件...", Toast.LENGTH_SHORT).show();
        
        new Thread(() -> {
            try {
                // 获取文件名
                String fileName = getFileName(uri);
                Log.d(TAG, "Importing file: " + fileName);
                
                // 确定目标目录
                String dataDir = getFilesDir().getAbsolutePath() + "/Dumb-Panel";
                String targetDir;
                
                if (fileName.contains("python")) {
                    targetDir = dataDir + "/deps/bin/python";
                } else if (fileName.contains("node")) {
                    targetDir = dataDir + "/deps/bin/node";
                } else {
                    handler.post(() -> Toast.makeText(this, "无法识别文件类型", Toast.LENGTH_LONG).show());
                    return;
                }
                
                // 创建目标目录
                File dir = new File(targetDir);
                if (dir.exists()) {
                    deleteRecursive(dir);
                }
                dir.mkdirs();
                
                // 解压文件
                InputStream is = getContentResolver().openInputStream(uri);
                if (fileName.endsWith(".tar.gz") || fileName.endsWith(".tgz")) {
                    // 解压 tar.gz
                    extractTarGz(is, targetDir);
                } else if (fileName.endsWith(".zip")) {
                    // 解压 zip
                    extractZip(is, targetDir);
                } else {
                    handler.post(() -> Toast.makeText(this, "不支持的文件格式", Toast.LENGTH_LONG).show());
                    return;
                }
                
                // 创建软链接
                if (fileName.contains("python")) {
                    createPythonSymlinks(targetDir + "/bin");
                }
                
                // 重新检查
                pythonInstalled = checkPythonInstalled();
                nodeInstalled = checkNodeInstalled();
                
                handler.post(() -> {
                    updatePythonStatus(pythonInstalled);
                    updateNodeStatus(nodeInstalled);
                    
                    if (pythonInstalled && nodeInstalled) {
                        showReady();
                        statusText.setText("导入完成！请点击启动面板");
                    } else {
                        statusText.setText("导入完成，请检查文件是否正确");
                    }
                    
                    Toast.makeText(this, "导入完成", Toast.LENGTH_LONG).show();
                });
                
            } catch (Exception e) {
                Log.e(TAG, "Import failed", e);
                handler.post(() -> Toast.makeText(this, "导入失败: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private String getFileName(Uri uri) {
        String fileName = "unknown";
        try {
            InputStream is = getContentResolver().openInputStream(uri);
            // 尝试从 URI 获取文件名
            String path = uri.getPath();
            if (path != null) {
                int lastSlash = path.lastIndexOf('/');
                if (lastSlash >= 0) {
                    fileName = path.substring(lastSlash + 1);
                }
            }
            if (is != null) is.close();
        } catch (Exception e) {
            // ignore
        }
        return fileName;
    }

    private void extractTarGz(InputStream is, String targetDir) throws IOException {
        // 使用 Runtime.exec 调用系统 tar 命令
        ProcessBuilder pb = new ProcessBuilder("tar", "xzf", "-", "-C", targetDir, "--strip-components=1");
        pb.redirectErrorStream(true);
        Process process = pb.start();
        
        OutputStream os = process.getOutputStream();
        byte[] buffer = new byte[4096];
        int bytesRead;
        while ((bytesRead = is.read(buffer)) != -1) {
            os.write(buffer, 0, bytesRead);
        }
        os.close();
        is.close();
        
        try {
            process.waitFor();
        } catch (InterruptedException e) {
            // ignore
        }
    }

    private void extractZip(InputStream is, String targetDir) throws IOException {
        // 简单的 zip 解压实现
        // 实际项目中建议使用 java.util.zip
        Toast.makeText(this, "ZIP 格式暂不支持，请使用 tar.gz 格式", Toast.LENGTH_LONG).show();
    }

    private void createPythonSymlinks(String binDir) {
        String[][] symlinks = {
            {"python", "python3.12"},
            {"python3", "python3.12"},
            {"pip", "pip3.12"},
            {"pip3", "pip3.12"}
        };
        
        for (String[] symlink : symlinks) {
            try {
                File link = new File(binDir, symlink[0]);
                File target = new File(binDir, symlink[1]);
                if (target.exists()) {
                    link.delete();
                    // 创建软链接
                    Runtime.getRuntime().exec("ln -sf " + symlink[1] + " " + link.getAbsolutePath());
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to create symlink", e);
            }
        }
    }

    private void deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        file.delete();
    }

    private void startMainActivity() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();
    }
}
