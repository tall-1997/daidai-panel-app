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
import android.os.PowerManager;
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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQUEST_CODE = 1001;
    private static final int OVERLAY_PERMISSION_REQUEST_CODE = 1002;
    
    // 静态变量，用于在 Activity 和 Service 之间共享 auth token
    public static String authToken = null;
    
    private WebView webView;
    private ProgressBar progressBar;
    private TextView statusText;
    private PanelManager panelManager;
    private Handler handler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        handler = new Handler(Looper.getMainLooper());
        
        initViews();
        panelManager = PanelManager.getInstance(this);
        
        // 初始化 Alpine 环境（后台执行）
        initAlpineEnvironment();
        
        checkPermissions();
        startLogOverlayService();
    }

    /**
     * 初始化 Alpine 环境
     */
    private void initAlpineEnvironment() {
        new Thread(() -> {
            try {
                String dataDir = getFilesDir().getAbsolutePath() + "/Dumb-Panel";
                
                // 检查 Alpine 是否已初始化（由 SplashActivity 完成）
                File alpineBin = new File(dataDir, "alpine/bin/sh");
                File proot = new File(dataDir, "proot");
                
                if (alpineBin.exists() && proot.exists()) {
                    Log.d(TAG, "Alpine environment already initialized by SplashActivity");
                    // 通知 Go 代码
                    PanelManager.getInstance(this).setAlpineReady(dataDir);
                } else {
                    Log.w(TAG, "Alpine environment not initialized");
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Failed to check Alpine environment", e);
            }
        }).start();
    }

    private void copyFile(File src, File dst) throws IOException {
        InputStream in = null;
        OutputStream out = null;
        try {
            in = new FileInputStream(src);
            out = new FileOutputStream(dst);
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();
        } finally {
            if (out != null) try { out.close(); } catch (IOException ignored) {}
            if (in != null) try { in.close(); } catch (IOException ignored) {}
        }
    }

    private void initViews() {
        webView = findViewById(R.id.webview);
        progressBar = findViewById(R.id.progress_bar);
        statusText = findViewById(R.id.status_text);
        
        webView.setVisibility(View.GONE);
        setupWebView();
    }

    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                super.onProgressChanged(view, newProgress);
                if (progressBar != null) {
                    progressBar.setProgress(newProgress);
                }
            }
        });
        
        // 添加 JavaScript Interface 用于获取 auth token
        webView.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface
            public void setAuthToken(String token) {
                authToken = token;
                Log.d(TAG, "Auth token updated");
            }
            
            @android.webkit.JavascriptInterface
            public String getAuthToken() {
                return authToken;
            }
        }, "AndroidBridge");
        
        // 页面加载完成后注入 JavaScript 来获取 token
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                Log.d(TAG, "Page finished: " + url);
                hideLoading();
                
                // 注入 JavaScript 来获取 auth token
                view.evaluateJavascript(
                    "(function() { " +
                    "  var token = localStorage.getItem('access_token'); " +
                    "  if (token && window.AndroidBridge) { " +
                    "    window.AndroidBridge.setAuthToken(token); " +
                    "  } " +
                    "  return token; " +
                    "})()",
                    null
                );
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
    }

    private void checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            boolean needRequest = false;
            
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.INTERNET)
                    != PackageManager.PERMISSION_GRANTED) {
                needRequest = true;
            }
            
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
            initPanel();
        }
    }

    private void startLogOverlayService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST_CODE);
            } else {
                startLogService();
            }
        } else {
            startLogService();
        }
    }

    private void startLogService() {
        try {
            Intent serviceIntent = new Intent(this, LogOverlayService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            Log.d(TAG, "LogOverlayService started");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start LogOverlayService", e);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == OVERLAY_PERMISSION_REQUEST_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                startLogService();
            }
        }
    }

    private void initPanel() {
        Log.d(TAG, "=== initPanel ===");
        showLoading("正在初始化面板...");
        
        checkBatteryOptimization();
        
        new Thread(() -> {
            // 复制前端资源
            copyWebAssetsSync();
            
            // 启动面板服务
            startPanelService();
        }).start();
    }

    private void checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            String packageName = getPackageName();
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            
            if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
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
                            Log.e(TAG, "Failed to open battery settings", e);
                        }
                    })
                    .setNegativeButton("暂不设置", null)
                    .show();
            }
        }
    }

    private void copyWebAssetsSync() {
        String webDir = getFilesDir().getAbsolutePath() + "/web";
        File webDirFile = new File(webDir);
        
        if (webDirFile.exists() && new File(webDir, "index.html").exists()) {
            Log.d(TAG, "Web assets exist, skip copy");
            return;
        }
        
        handler.post(() -> showLoading("正在复制前端资源..."));
        
        try {
            copyAssetFolder("web", webDir);
            Log.d(TAG, "Web assets copied");
        } catch (IOException e) {
            Log.e(TAG, "Failed to copy web assets", e);
        }
    }

    private void copyAssetFolder(String assetFolder, String targetFolder) throws IOException {
        File targetDir = new File(targetFolder);
        if (!targetDir.exists()) {
            targetDir.mkdirs();
        }
        
        String[] files = getAssets().list(assetFolder);
        if (files == null || files.length == 0) {
            copyAssetFile(assetFolder, targetFolder);
            return;
        }
        
        for (String file : files) {
            String assetPath = assetFolder + "/" + file;
            String targetPath = targetFolder + "/" + file;
            
            String[] subFiles = getAssets().list(assetPath);
            if (subFiles != null && subFiles.length > 0) {
                copyAssetFolder(assetPath, targetPath);
            } else {
                copyAssetFile(assetPath, targetPath);
            }
        }
    }

    private void copyAssetFile(String assetPath, String targetPath) throws IOException {
        File targetFile = new File(targetPath);
        File parentDir = targetFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }
        
        InputStream in = null;
        OutputStream out = null;
        try {
            in = getAssets().open(assetPath);
            out = new FileOutputStream(targetPath);
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();
        } finally {
            if (out != null) try { out.close(); } catch (IOException ignored) {}
            if (in != null) try { in.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * 启动面板服务
     */
    private void startPanelService() {
        String dataDir = getFilesDir().getAbsolutePath() + "/Dumb-Panel";
        String webDir = getFilesDir().getAbsolutePath() + "/web";
        int port = 5701;
        
        Log.d(TAG, "=== startPanelService ===");
        Log.d(TAG, "Data dir: " + dataDir);
        Log.d(TAG, "Web dir: " + webDir);
        
        handler.post(() -> showLoading("正在启动面板服务..."));
        
        // 初始化目录
        panelManager.initDataDir(dataDir);
        
        // 启动服务器
        panelManager.startServer(dataDir, webDir, port);
        
        // 等待服务器启动
        waitForServer();
    }

    /**
     * 等待服务器启动
     */
    private void waitForServer() {
        Log.d(TAG, "=== waitForServer ===");
        
        new Thread(() -> {
            int maxWait = 60;
            int waited = 0;
            
            while (waited < maxWait) {
                try {
                    Thread.sleep(1000);
                    waited++;
                    
                    boolean running = panelManager.isServerRunning();
                    Log.d(TAG, "Server check: " + running + " (" + waited + "s)");
                    
                    if (running) {
                        Log.d(TAG, "=== Server ready! ===");
                        handler.post(() -> loadPanel());
                        return;
                    }
                    
                    final int progress = waited;
                    handler.post(() -> showLoading("正在启动面板服务... (" + progress + "s)"));
                } catch (InterruptedException e) {
                    break;
                }
            }
            
            Log.e(TAG, "=== Server timeout ===");
            handler.post(() -> showError("面板服务启动超时，请重启应用"));
        }).start();
    }

    /**
     * 加载面板页面
     */
    private void loadPanel() {
        String url = panelManager.getServerURL();
        Log.d(TAG, "=== loadPanel ===");
        Log.d(TAG, "URL: " + url);
        
        showLoading("正在加载面板页面...");
        
        webView.setVisibility(View.VISIBLE);
        webView.loadUrl(url);
    }

    private void showLoading(String message) {
        runOnUiThread(() -> {
            if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
            if (statusText != null) {
                statusText.setVisibility(View.VISIBLE);
                statusText.setText(message);
                statusText.setTextColor(0xFF666666);
            }
            if (webView != null) webView.setVisibility(View.GONE);
        });
    }

    private void hideLoading() {
        runOnUiThread(() -> {
            if (progressBar != null) progressBar.setVisibility(View.GONE);
            if (statusText != null) statusText.setVisibility(View.GONE);
            if (webView != null) webView.setVisibility(View.VISIBLE);
        });
    }

    private void showError(String message) {
        runOnUiThread(() -> {
            if (progressBar != null) progressBar.setVisibility(View.GONE);
            if (statusText != null) {
                statusText.setVisibility(View.VISIBLE);
                statusText.setText(message);
                statusText.setTextColor(0xFFFF0000);
            }
            if (webView != null) webView.setVisibility(View.GONE);
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
        if (webView != null) webView.onResume();
        // 显示悬浮窗
        sendBroadcast(new Intent("com.daidai.panel.SHOW_OVERLAY"));
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) webView.onPause();
        // 隐藏悬浮窗
        sendBroadcast(new Intent("com.daidai.panel.HIDE_OVERLAY"));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (webView != null) webView.destroy();
    }
}
