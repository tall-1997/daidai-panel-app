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
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class SplashActivity extends AppCompatActivity {
    private static final String TAG = "SplashActivity";
    
    // 阿里云镜像源
    private static final String PYTHON_URL = "https://mirrors.aliyun.com/python-build-standalone/20240415/cpython-3.12.3+20240415-aarch64-unknown-linux-gnu-install_only.tar.gz";
    private static final String NODE_URL = "https://npmmirror.com/mirrors/node/v20.17.0/node-v20.17.0-linux-arm64.tar.gz";
    
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
    
    private ActivityResultLauncher<Intent> filePickerLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);
        
        handler = new Handler(Looper.getMainLooper());
        
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
        
        // 初始化 Alpine 环境
        statusText.setText("正在初始化环境...");
        new Thread(() -> {
            initAlpineEnvironment();
            
            handler.post(() -> {
                checkDependencies();
            });
        }).start();
    }

    /**
     * 初始化 Alpine 环境
     */
    private void initAlpineEnvironment() {
        try {
            String dataDir = getFilesDir().getAbsolutePath() + "/Dumb-Panel";
            
            // 解压 Alpine rootfs
            File alpineDir = new File(dataDir, "alpine");
            File alpineBin = new File(alpineDir, "bin/sh");
            
            if (!alpineBin.exists()) {
                Log.d(TAG, "Extracting Alpine rootfs from assets...");
                alpineDir.mkdirs();
                
                // 从 assets 解压（注意：APK 中可能是 .tar 或 .tar.gz）
                InputStream in = null;
                try {
                    in = getAssets().open("alpine/alpine-rootfs.tar.gz");
                } catch (IOException e) {
                    Log.d(TAG, "Trying .tar extension...");
                    in = getAssets().open("alpine/alpine-rootfs.tar");
                }
                
                // 保存到临时文件
                File tmpFile = new File(dataDir, "alpine-rootfs.tar");
                FileOutputStream fos = new FileOutputStream(tmpFile);
                byte[] buffer = new byte[4096];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    fos.write(buffer, 0, read);
                }
                fos.flush();
                fos.close();
                in.close();
                
                // 解压
                ProcessBuilder pb = new ProcessBuilder("tar", "xf", 
                    tmpFile.getAbsolutePath(), "-C", alpineDir.getAbsolutePath());
                Process process = pb.start();
                int exitCode = process.waitFor();
                Log.d(TAG, "Alpine rootfs extract exit code: " + exitCode);
                
                // 删除临时文件
                tmpFile.delete();
                
                if (alpineBin.exists()) {
                    Log.d(TAG, "Alpine rootfs extracted successfully");
                } else {
                    Log.e(TAG, "Alpine rootfs extraction failed");
                }
            } else {
                Log.d(TAG, "Alpine rootfs already exists");
            }
            
            // 设置 DNS
            File resolvConf = new File(alpineDir, "etc/resolv.conf");
            if (!resolvConf.exists()) {
                resolvConf.getParentFile().mkdirs();
                FileOutputStream fos = new FileOutputStream(resolvConf);
                fos.write("nameserver 8.8.8.8\nnameserver 8.8.4.4\n".getBytes());
                fos.close();
            }
            
            Log.d(TAG, "Alpine environment initialized");
            
            // 通知 Go 代码 Alpine 环境已就绪
            // proot 在 nativeLibraryDir 中，有执行权限
            String nativeLibDir = getApplicationInfo().nativeLibraryDir;
            String prootPath = new File(nativeLibDir, "libproot.so").getAbsolutePath();
            Log.d(TAG, "Notifying Go that Alpine is ready, proot: " + prootPath);
            PanelManager.getInstance(this).setAlpineReady(dataDir, prootPath);
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to init Alpine environment", e);
        }
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
            try { Thread.sleep(500); } catch (InterruptedException e) { /* ignore */ }
            
            pythonInstalled = checkPythonInstalled();
            nodeInstalled = checkNodeInstalled();
            
            handler.post(() -> {
                updatePythonStatus(pythonInstalled);
                updateNodeStatus(nodeInstalled);
                
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
        return new File(pythonBin).exists();
    }

    private boolean checkNodeInstalled() {
        String dataDir = getFilesDir().getAbsolutePath() + "/Dumb-Panel";
        String nodeBin = dataDir + "/deps/bin/node/bin/node";
        return new File(nodeBin).exists();
    }

    private void updatePythonStatus(boolean installed) {
        pythonStatus.setText(installed ? "已安装" : "未安装");
        pythonStatus.setTextColor(installed ? 0xFF4CAF50 : 0xFFFF9800);
    }

    private void updateNodeStatus(boolean installed) {
        nodeStatus.setText(installed ? "已安装" : "未安装");
        nodeStatus.setTextColor(installed ? 0xFF4CAF50 : 0xFFFF9800);
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
        statusText.setText("正在下载运行时...");
        
        new Thread(() -> {
            String dataDir = getFilesDir().getAbsolutePath() + "/Dumb-Panel";
            
            // 下载 Python
            if (!pythonInstalled) {
                handler.post(() -> {
                    downloadStatus.setText("正在下载 Python...");
                    downloadProgress.setProgress(0);
                    downloadPercent.setText("0%");
                });
                
                boolean success = downloadAndExtract(PYTHON_URL, 
                    dataDir + "/deps/bin/python", 
                    "python");
                
                if (success) {
                    createPythonSymlinks(dataDir + "/deps/bin/python/bin");
                }
            }
            
            // 下载 Node.js
            if (!nodeInstalled) {
                handler.post(() -> {
                    downloadStatus.setText("正在下载 Node.js...");
                    downloadProgress.setProgress(0);
                    downloadPercent.setText("0%");
                });
                
                downloadAndExtract(NODE_URL, 
                    dataDir + "/deps/bin/node", 
                    "node");
            }
            
            // 重新检查
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
                    Toast.makeText(this, "运行时下载完成", Toast.LENGTH_LONG).show();
                } else {
                    downloadButton.setEnabled(true);
                    downloadButton.setText("重试下载");
                    statusText.setText("下载失败，请重试或本地导入");
                    Toast.makeText(this, "下载失败，请检查网络", Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private boolean downloadAndExtract(String urlStr, String targetDir, String name) {
        try {
            Log.d(TAG, "Downloading: " + urlStr);
            
            // 清理旧目录
            File dir = new File(targetDir);
            if (dir.exists()) {
                deleteRecursive(dir);
            }
            dir.mkdirs();
            
            // 下载文件
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(300000);
            conn.setRequestProperty("User-Agent", "DaidaiPanel/1.0");
            
            int responseCode = conn.getResponseCode();
            Log.d(TAG, "Response code: " + responseCode);
            
            if (responseCode != 200) {
                Log.e(TAG, "Download failed: HTTP " + responseCode);
                handler.post(() -> Toast.makeText(this, "下载失败: HTTP " + responseCode, Toast.LENGTH_LONG).show());
                return false;
            }
            
            long fileSize = conn.getContentLengthLong();
            Log.d(TAG, "File size: " + fileSize);
            
            // 下载到临时文件
            File tmpFile = new File(targetDir + ".tar.gz");
            FileOutputStream fos = new FileOutputStream(tmpFile);
            InputStream is = conn.getInputStream();
            
            byte[] buffer = new byte[8192];
            long totalRead = 0;
            int bytesRead;
            int lastProgress = 0;
            
            while ((bytesRead = is.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
                totalRead += bytesRead;
                
                // 更新进度
                if (fileSize > 0) {
                    int progress = (int) (totalRead * 100 / fileSize);
                    if (progress != lastProgress) {
                        lastProgress = progress;
                        final int p = progress;
                        final long downloaded = totalRead;
                        handler.post(() -> {
                            downloadProgress.setProgress(p);
                            downloadPercent.setText(p + "%");
                            downloadStatus.setText(String.format("正在下载 %s... (%.1f MB)", 
                                name, downloaded / 1024.0 / 1024.0));
                        });
                    }
                }
            }
            
            fos.flush();
            fos.close();
            is.close();
            conn.disconnect();
            
            Log.d(TAG, "Download complete: " + tmpFile.getAbsolutePath());
            
            // 解压
            handler.post(() -> downloadStatus.setText("正在解压 " + name + "..."));
            
            ProcessBuilder pb = new ProcessBuilder(
                "tar", "xzf", tmpFile.getAbsolutePath(), 
                "-C", targetDir, 
                "--strip-components=1"
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            // 读取输出
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            while (reader.readLine() != null) { /* drain */ }
            
            int exitCode = process.waitFor();
            Log.d(TAG, "Extract exit code: " + exitCode);
            
            // 删除临时文件
            tmpFile.delete();
            
            if (exitCode == 0) {
                Log.d(TAG, "Extract complete: " + targetDir);
                return true;
            } else {
                Log.e(TAG, "Extract failed");
                return false;
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Download failed", e);
            handler.post(() -> Toast.makeText(this, "下载失败: " + e.getMessage(), Toast.LENGTH_LONG).show());
            return false;
        }
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
                    new ProcessBuilder("ln", "-sf", symlink[1], link.getAbsolutePath())
                        .start()
                        .waitFor();
                    Log.d(TAG, "Created symlink: " + symlink[0] + " -> " + symlink[1]);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to create symlink", e);
            }
        }
    }

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("application/gzip");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        filePickerLauncher.launch(Intent.createChooser(intent, "选择运行时文件 (.tar.gz)"));
    }

    private void handleImportedFile(Uri uri) {
        Toast.makeText(this, "正在导入文件...", Toast.LENGTH_SHORT).show();
        statusText.setText("正在导入...");
        
        new Thread(() -> {
            try {
                String fileName = getFileName(uri);
                Log.d(TAG, "Importing file: " + fileName);
                
                String dataDir = getFilesDir().getAbsolutePath() + "/Dumb-Panel";
                String targetDir;
                
                if (fileName.contains("python")) {
                    targetDir = dataDir + "/deps/bin/python";
                } else if (fileName.contains("node")) {
                    targetDir = dataDir + "/deps/bin/node";
                } else {
                    handler.post(() -> Toast.makeText(this, "无法识别文件类型，请包含 python 或 node 关键字", Toast.LENGTH_LONG).show());
                    return;
                }
                
                // 清理旧目录
                File dir = new File(targetDir);
                if (dir.exists()) deleteRecursive(dir);
                dir.mkdirs();
                
                // 解压
                InputStream is = getContentResolver().openInputStream(uri);
                ProcessBuilder pb = new ProcessBuilder(
                    "tar", "xzf", "-", 
                    "-C", targetDir, 
                    "--strip-components=1"
                );
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
                
                int exitCode = process.waitFor();
                Log.d(TAG, "Extract exit code: " + exitCode);
                
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
        String path = uri.getPath();
        if (path != null) {
            int lastSlash = path.lastIndexOf('/');
            if (lastSlash >= 0) {
                return path.substring(lastSlash + 1);
            }
        }
        return "unknown.tar.gz";
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
