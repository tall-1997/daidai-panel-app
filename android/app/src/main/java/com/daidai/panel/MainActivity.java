package com.daidai.panel;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 主Activity
 */
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQUEST_CODE = 1001;
    
    private WebView webView;
    private ProgressBar progressBar;
    private TextView statusText;
    private PanelManager panelManager;
    private Handler handler;
    private boolean isServerStarted = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        handler = new Handler(Looper.getMainLooper());
        
        // 初始化视图
        initViews();
        
        // 初始化面板管理器
        panelManager = new PanelManager(this);
        
        // 检查权限
        checkPermissions();
    }

    private void initViews() {
        webView = findViewById(R.id.webview);
        progressBar = findViewById(R.id.progress_bar);
        statusText = findViewById(R.id.status_text);
        
        // 配置WebView
        setupWebView();
    }

    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        
        // 启用JavaScript
        settings.setJavaScriptEnabled(true);
        
        // 启用DOM存储
        settings.setDomStorageEnabled(true);
        
        // 启用文件访问
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        
        // 启用缩放
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        
        // 设置缓存模式
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        
        // 允许混合内容（HTTP在本地）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        
        // 设置WebViewClient
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                Log.d(TAG, "Page finished loading: " + url);
                hideLoading();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                Log.e(TAG, "WebView error: " + error.getDescription());
                
                if (request.isForMainFrame()) {
                    showError("页面加载失败: " + error.getDescription());
                }
            }
        });
        
        // 设置WebChromeClient
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                super.onProgressChanged(view, newProgress);
                if (progressBar != null) {
                    progressBar.setProgress(newProgress);
                }
            }
        });
    }

    private void checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            boolean needRequest = false;
            
            // 检查网络权限
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.INTERNET)
                    != PackageManager.PERMISSION_GRANTED) {
                needRequest = true;
            }
            
            // Android 13+ 需要通知权限
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
                    needRequest = true;
                }
            }
            
            if (needRequest) {
                ActivityCompat.requestPermissions(this,
                    new String[]{
                        Manifest.permission.INTERNET,
                        Manifest.permission.POST_NOTIFICATIONS
                    },
                    PERMISSION_REQUEST_CODE
            );
            } else {
                initPanel();
            }
        } else {
            initPanel();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        
        if (requestCode == PERMISSION_REQUEST_CODE) {
            // 无论权限是否授予，都继续初始化
            initPanel();
        }
    }

    private void initPanel() {
        showLoading("正在初始化面板...");
        
        // 检查是否需要忽略电池优化
        checkBatteryOptimization();
        
        // 复制前端资源
        copyWebAssets();
        
        // 启动面板服务
        startPanelService();
    }

    private void checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            String packageName = getPackageName();
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            
            if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
                // 显示提示对话框
                new AlertDialog.Builder(this)
                    .setTitle("电池优化设置")
                    .setMessage("为了确保面板服务在后台正常运行，建议关闭电池优化。")
                    .setPositiveButton("去设置", (dialog, which) -> {
                        try {
                            Intent intent = new Intent();
                            intent.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                            intent.setData(Uri.parse("package:" + packageName));
                            startActivity(intent);
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to open battery optimization settings", e);
                        }
                    })
                    .setNegativeButton("暂不设置", null)
                    .show();
            }
        }
    }

    private void copyWebAssets() {
        String webDir = getFilesDir().getAbsolutePath() + "/web";
        File webDirFile = new File(webDir);
        
        // 如果web目录已存在且有index.html，跳过复制
        if (webDirFile.exists() && new File(webDir, "index.html").exists()) {
            Log.d(TAG, "Web assets already exist");
            return;
        }
        
        showLoading("正在复制前端资源...");
        
        new Thread(() -> {
            try {
                copyAssetFolder("web", webDir);
                Log.d(TAG, "Web assets copied successfully");
            } catch (IOException e) {
                Log.e(TAG, "Failed to copy web assets", e);
            }
        }).start();
    }

    private void copyAssetFolder(String assetFolder, String targetFolder) throws IOException {
        File targetDir = new File(targetFolder);
        if (!targetDir.exists()) {
            targetDir.mkdirs();
        }
        
        String[] files = getAssets().list(assetFolder);
        if (files == null || files.length == 0) {
            // 复制文件
            copyAssetFile(assetFolder, targetFolder);
            return;
        }
        
        for (String file : files) {
            String assetPath = assetFolder + "/" + file;
            String targetPath = targetFolder + "/" + file;
            
            String[] subFiles = getAssets().list(assetPath);
            if (subFiles != null && subFiles.length > 0) {
                // 是目录，递归复制
                copyAssetFolder(assetPath, targetPath);
            } else {
                // 是文件，直接复制
                copyAssetFile(assetPath, targetPath);
            }
        }
    }

    private void copyAssetFile(String assetPath, String targetPath) throws IOException {
        InputStream in = getAssets().open(assetPath);
        OutputStream out = new FileOutputStream(targetPath);
        
        byte[] buffer = new byte[1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        
        in.close();
        out.close();
    }

    private void startPanelService() {
        showLoading("正在启动面板服务...");
        
        // 启动前台服务
        Intent serviceIntent = new Intent(this, PanelService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        
        // 等待服务启动
        waitForServer();
    }

    private void waitForServer() {
        new Thread(() -> {
            int maxWait = 30; // 最多等待30秒
            int waited = 0;
            
            while (!panelManager.isServerRunning() && waited < maxWait) {
                try {
                    Thread.sleep(1000);
                    waited++;
                    
                    final int progress = waited;
                    handler.post(() -> {
                        showLoading("正在启动面板服务... (" + progress + "s)");
                    });
                } catch (InterruptedException e) {
                    break;
                }
            }
            
            handler.post(() -> {
                if (panelManager.isServerRunning()) {
                    loadPanel();
                } else {
                    showError("面板服务启动超时");
                }
            });
        }).start();
    }

    private void loadPanel() {
        isServerStarted = true;
        String url = panelManager.getServerURL();
        
        Log.d(TAG, "Loading panel: " + url);
        showLoading("正在加载面板页面...");
        
        webView.loadUrl(url);
    }

    private void showLoading(String message) {
        runOnUiThread(() -> {
            if (progressBar != null) {
                progressBar.setVisibility(View.VISIBLE);
            }
            if (statusText != null) {
                statusText.setVisibility(View.VISIBLE);
                statusText.setText(message);
            }
        });
    }

    private void hideLoading() {
        runOnUiThread(() -> {
            if (progressBar != null) {
                progressBar.setVisibility(View.GONE);
            }
            if (statusText != null) {
                statusText.setVisibility(View.GONE);
            }
        });
    }

    private void showError(String message) {
        runOnUiThread(() -> {
            if (progressBar != null) {
                progressBar.setVisibility(View.GONE);
            }
            if (statusText != null) {
                statusText.setVisibility(View.VISIBLE);
                statusText.setText(message);
            }
        });
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        webView.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        
        if (webView != null) {
            webView.destroy();
        }
    }
}
