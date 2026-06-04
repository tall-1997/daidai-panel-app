package com.daidai.launcher;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "DaidaiLauncher";
    private static final String TERMUX_PACKAGE = "com.termux";
    private static final String TERMUX_BOOT_SCRIPT = "init-daidai.sh";
    
    private TextView statusText;
    private Button startButton;
    private Handler handler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        handler = new Handler(Looper.getMainLooper());
        
        statusText = findViewById(R.id.status_text);
        startButton = findViewById(R.id.start_button);
        
        startButton.setOnClickListener(v -> startPanel());
        
        // 检查 Termux 是否已安装
        checkTermux();
    }

    private void checkTermux() {
        if (isTermuxInstalled()) {
            statusText.setText("Termux 已安装，点击启动面板");
            startButton.setEnabled(true);
            
            // 复制初始化脚本到 Termux
            copyInitScript();
        } else {
            statusText.setText("请先安装 Termux");
            startButton.setText("安装 Termux");
            startButton.setOnClickListener(v -> installTermux());
        }
    }

    private boolean isTermuxInstalled() {
        try {
            getPackageManager().getPackageInfo(TERMUX_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void installTermux() {
        try {
            // 尝试从 F-Droid 安装
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse("https://f-droid.org/packages/com.termux/"));
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "请手动安装 Termux", Toast.LENGTH_LONG).show();
        }
    }

    private void copyInitScript() {
        try {
            // 读取 assets 中的初始化脚本
            InputStream in = getAssets().open("scripts/" + TERMUX_BOOT_SCRIPT);
            
            // Termux 的 home 目录
            File termuxHome = new File("/data/data/com.termux/files/home");
            if (!termuxHome.exists()) {
                termuxHome.mkdirs();
            }
            
            File scriptFile = new File(termuxHome, TERMUX_BOOT_SCRIPT);
            FileOutputStream fos = new FileOutputStream(scriptFile);
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                fos.write(buffer, 0, read);
            }
            fos.flush();
            fos.close();
            in.close();
            
            // 设置执行权限
            scriptFile.setExecutable(true, false);
            
            Log.d(TAG, "Init script copied to: " + scriptFile.getAbsolutePath());
        } catch (IOException e) {
            Log.e(TAG, "Failed to copy init script", e);
        }
    }

    private void startPanel() {
        try {
            // 启动 Termux 并执行初始化脚本
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(TERMUX_PACKAGE, "com.termux.app.TermuxActivity"));
            intent.setAction(Intent.ACTION_VIEW);
            intent.putExtra("com.termux.RUN_COMMAND", "/data/data/com.termux/files/home/" + TERMUX_BOOT_SCRIPT);
            startActivity(intent);
            
            // 延迟打开浏览器
            handler.postDelayed(() -> {
                Intent browserIntent = new Intent(Intent.ACTION_VIEW);
                browserIntent.setData(Uri.parse("http://127.0.0.1:5700"));
                startActivity(browserIntent);
            }, 5000);
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to start Termux", e);
            Toast.makeText(this, "启动失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
