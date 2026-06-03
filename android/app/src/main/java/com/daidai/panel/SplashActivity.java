package com.daidai.panel;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class SplashActivity extends AppCompatActivity {
    private static final String TAG = "SplashActivity";
    
    private TextView statusText;
    private ProgressBar progressBar;
    private TextView progressText;
    private ImageView pythonIcon;
    private TextView pythonStatus;
    private ImageView nodeIcon;
    private TextView nodeStatus;
    private Button actionButton;
    private Button skipButton;
    
    private Handler handler;
    private boolean pythonInstalled = false;
    private boolean nodeInstalled = false;
    private boolean isInstalling = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);
        
        handler = new Handler(Looper.getMainLooper());
        
        initViews();
        checkDependencies();
    }

    private void initViews() {
        statusText = findViewById(R.id.status_text);
        progressBar = findViewById(R.id.progress_bar);
        progressText = findViewById(R.id.progress_text);
        pythonIcon = findViewById(R.id.python_icon);
        pythonStatus = findViewById(R.id.python_status);
        nodeIcon = findViewById(R.id.node_icon);
        nodeStatus = findViewById(R.id.node_status);
        actionButton = findViewById(R.id.action_button);
        skipButton = findViewById(R.id.skip_button);
        
        actionButton.setOnClickListener(v -> onActionClick());
        skipButton.setOnClickListener(v -> startMainActivity());
    }

    private void checkDependencies() {
        statusText.setText("正在检查环境...");
        
        new Thread(() -> {
            // 检查 Python
            pythonInstalled = checkPythonInstalled();
            handler.post(() -> updatePythonStatus(pythonInstalled));
            
            // 检查 Node.js
            nodeInstalled = checkNodeInstalled();
            handler.post(() -> updateNodeStatus(nodeInstalled));
            
            // 更新 UI
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
        String pythonBin = dataDir + "/deps/bin/python/bin/python3";
        File file = new File(pythonBin);
        boolean exists = file.exists() && file.canExecute();
        Log.d(TAG, "Python installed: " + exists + " (" + pythonBin + ")");
        return exists;
    }

    private boolean checkNodeInstalled() {
        String dataDir = getFilesDir().getAbsolutePath() + "/Dumb-Panel";
        String nodeBin = dataDir + "/deps/bin/node/bin/node";
        File file = new File(nodeBin);
        boolean exists = file.exists() && file.canExecute();
        Log.d(TAG, "Node installed: " + exists + " (" + nodeBin + ")");
        return exists;
    }

    private void updatePythonStatus(boolean installed) {
        if (installed) {
            pythonIcon.setImageResource(android.R.drawable.ic_dialog_info);
            pythonStatus.setText("已安装");
            pythonStatus.setTextColor(0xFF4CAF50);
        } else {
            pythonIcon.setImageResource(android.R.drawable.ic_dialog_alert);
            pythonStatus.setText("未安装");
            pythonStatus.setTextColor(0xFFFF9800);
        }
    }

    private void updateNodeStatus(boolean installed) {
        if (installed) {
            nodeIcon.setImageResource(android.R.drawable.ic_dialog_info);
            nodeStatus.setText("已安装");
            nodeStatus.setTextColor(0xFF4CAF50);
        } else {
            nodeIcon.setImageResource(android.R.drawable.ic_dialog_alert);
            nodeStatus.setText("未安装");
            nodeStatus.setTextColor(0xFFFF9800);
        }
    }

    private void showReady() {
        statusText.setText("环境检查完成");
        actionButton.setText("启动面板");
        actionButton.setVisibility(View.VISIBLE);
        skipButton.setVisibility(View.GONE);
    }

    private void showInstallNeeded() {
        statusText.setText("需要安装运行时");
        actionButton.setText("一键安装");
        actionButton.setVisibility(View.VISIBLE);
        skipButton.setVisibility(View.VISIBLE);
    }

    private void onActionClick() {
        if (pythonInstalled && nodeInstalled) {
            startMainActivity();
        } else {
            installMissingRuntimes();
        }
    }

    private void installMissingRuntimes() {
        if (isInstalling) return;
        isInstalling = true;
        
        actionButton.setEnabled(false);
        actionButton.setText("安装中...");
        progressBar.setVisibility(View.VISIBLE);
        progressText.setVisibility(View.VISIBLE);
        
        new Thread(() -> {
            if (!pythonInstalled) {
                installRuntime("python");
            }
            
            if (!nodeInstalled) {
                installRuntime("node");
            }
            
            // 重新检查
            pythonInstalled = checkPythonInstalled();
            nodeInstalled = checkNodeInstalled();
            
            handler.post(() -> {
                isInstalling = false;
                progressBar.setVisibility(View.GONE);
                progressText.setVisibility(View.GONE);
                
                updatePythonStatus(pythonInstalled);
                updateNodeStatus(nodeInstalled);
                
                if (pythonInstalled && nodeInstalled) {
                    showReady();
                    statusText.setText("安装完成！请点击启动面板");
                } else {
                    actionButton.setEnabled(true);
                    actionButton.setText("重试安装");
                    statusText.setText("安装失败，请重试");
                }
            });
        }).start();
    }

    private void installRuntime(String name) {
        Log.d(TAG, "Installing " + name + "...");
        handler.post(() -> {
            statusText.setText("正在安装 " + name + "...");
            progressText.setText("正在下载...");
        });
        
        try {
            // 获取 auth token
            String token = MainActivity.authToken;
            if (token == null) {
                Log.e(TAG, "Auth token is null");
                return;
            }
            
            // 调用安装 API
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
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("data: ")) {
                        String msg = line.substring(6);
                        Log.d(TAG, "SSE: " + msg);
                        handler.post(() -> progressText.setText(msg));
                    }
                }
                reader.close();
            }
            
            conn.disconnect();
            
        } catch (Exception e) {
            Log.e(TAG, "Install failed", e);
        }
    }

    private void startMainActivity() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();
    }
}
