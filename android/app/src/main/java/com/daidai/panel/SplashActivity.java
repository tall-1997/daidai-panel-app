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
            try {
                Thread.sleep(500); // 短暂延迟让用户看到检查状态
            } catch (InterruptedException e) {
                // ignore
            }
            
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
        boolean exists = file.exists();
        boolean canExecute = exists && file.canExecute();
        Log.d(TAG, "Python check: exists=" + exists + ", canExecute=" + canExecute + " (" + pythonBin + ")");
        
        // 列出目录内容
        File binDir = new File(dataDir + "/deps/bin/python/bin");
        if (binDir.exists()) {
            String[] files = binDir.list();
            if (files != null) {
                Log.d(TAG, "Python bin dir contents: " + String.join(", ", files));
            }
        } else {
            Log.d(TAG, "Python bin dir not exists: " + binDir.getAbsolutePath());
        }
        
        return canExecute;
    }

    private boolean checkNodeInstalled() {
        String dataDir = getFilesDir().getAbsolutePath() + "/Dumb-Panel";
        String nodeBin = dataDir + "/deps/bin/node/bin/node";
        File file = new File(nodeBin);
        boolean exists = file.exists();
        boolean canExecute = exists && file.canExecute();
        Log.d(TAG, "Node check: exists=" + exists + ", canExecute=" + canExecute + " (" + nodeBin + ")");
        
        // 列出目录内容
        File binDir = new File(dataDir + "/deps/bin/node/bin");
        if (binDir.exists()) {
            String[] files = binDir.list();
            if (files != null) {
                Log.d(TAG, "Node bin dir contents: " + String.join(", ", files));
            }
        } else {
            Log.d(TAG, "Node bin dir not exists: " + binDir.getAbsolutePath());
        }
        
        return canExecute;
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
        actionButton.setText("启动面板并安装");
        actionButton.setVisibility(View.VISIBLE);
        skipButton.setVisibility(View.GONE);
    }

    private void onActionClick() {
        // 直接启动面板，在面板中安装运行时
        startMainActivity();
    }

    private void startMainActivity() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();
    }
}
