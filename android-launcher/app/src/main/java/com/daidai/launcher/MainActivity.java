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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "DaidaiLauncher";
    private static final String TERMUX_PACKAGE = "com.termux";
    private static final String TERMUX_PACKAGE_LEGACY = "com.termux.debug";
    private static final String TERMUX_BOOT_SCRIPT = "init-daidai.sh";
    
    private TextView statusText;
    private Button startButton;
    private Handler handler;
    private String detectedTermuxPackage = null;

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
        // 检查所有可能的 Termux 包名
        String[] termuxPackages = {
            "com.termux",
            "com.termux.debug", 
            "com.termux.nightly",
            "com.termux.view"
        };
        
        for (String pkg : termuxPackages) {
            try {
                getPackageManager().getPackageInfo(pkg, 0);
                detectedTermuxPackage = pkg;
                Log.d(TAG, "Found Termux package: " + pkg);
                break;
            } catch (PackageManager.NameNotFoundException e) {
                // 继续检查下一个
            }
        }
        
        if (detectedTermuxPackage != null) {
            statusText.setText("Termux 已安装 (" + detectedTermuxPackage + ")\n点击启动面板");
            startButton.setEnabled(true);
            
            // 复制初始化脚本到 Termux
            copyInitScript();
        } else {
            statusText.setText("请先安装 Termux\n当前未检测到 Termux");
            startButton.setText("安装 Termux");
            startButton.setOnClickListener(v -> installTermux());
        }
    }

    private boolean isTermuxInstalled() {
        return detectedTermuxPackage != null;
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
            
            // 先复制到启动器自己的目录
            File launcherDir = getFilesDir();
            File scriptFile = new File(launcherDir, TERMUX_BOOT_SCRIPT);
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
            scriptFile.setReadable(true, false);
            
            Log.d(TAG, "Init script copied to: " + scriptFile.getAbsolutePath());
            
            // 同时复制到公共目录（Termux 可以访问）
            try {
                // 使用 /sdcard/Download 目录
                File publicDir = new File("/sdcard/Download");
                if (publicDir.exists() && publicDir.canWrite()) {
                    File publicScript = new File(publicDir, TERMUX_BOOT_SCRIPT);
                    FileInputStream fis = new FileInputStream(scriptFile);
                    FileOutputStream publicFos = new FileOutputStream(publicScript);
                    byte[] buf = new byte[4096];
                    int len;
                    while ((len = fis.read(buf)) > 0) {
                        publicFos.write(buf, 0, len);
                    }
                    publicFos.flush();
                    publicFos.close();
                    fis.close();
                    publicScript.setExecutable(true, false);
                    publicScript.setReadable(true, false);
                    Log.d(TAG, "Script also copied to public: " + publicScript.getAbsolutePath());
                }
            } catch (Exception e) {
                Log.w(TAG, "Cannot write to public directory", e);
            }
            
            // 同时尝试复制到 Termux 目录
            try {
                String termuxDataDir = "/data/data/" + detectedTermuxPackage;
                File termuxHome = new File(termuxDataDir, "files/home");
                if (termuxHome.exists() && termuxHome.canWrite()) {
                    File termuxScript = new File(termuxHome, TERMUX_BOOT_SCRIPT);
                    FileInputStream fis = new FileInputStream(scriptFile);
                    FileOutputStream termuxFos = new FileOutputStream(termuxScript);
                    byte[] buf = new byte[4096];
                    int len;
                    while ((len = fis.read(buf)) > 0) {
                        termuxFos.write(buf, 0, len);
                    }
                    termuxFos.flush();
                    termuxFos.close();
                    fis.close();
                    termuxScript.setExecutable(true, false);
                    Log.d(TAG, "Script also copied to Termux: " + termuxScript.getAbsolutePath());
                }
            } catch (Exception e) {
                Log.w(TAG, "Cannot write to Termux directory", e);
            }
            
        } catch (IOException e) {
            Log.e(TAG, "Failed to copy init script", e);
        }
    }

    private void startPanel() {
        try {
            if (detectedTermuxPackage == null) {
                Toast.makeText(this, "未检测到 Termux", Toast.LENGTH_LONG).show();
                return;
            }
            
            // 先复制脚本
            copyInitScript();
            
            // 获取脚本路径（使用公共目录）
            String scriptPath = "/sdcard/Download/" + TERMUX_BOOT_SCRIPT;
            
            Log.d(TAG, "Starting Termux with package: " + detectedTermuxPackage);
            Log.d(TAG, "Script path: " + scriptPath);
            
            // 使用 Termux RUN_COMMAND Intent
            try {
                Intent intent = new Intent();
                intent.setClassName(detectedTermuxPackage, detectedTermuxPackage + ".app.RunCommandService");
                intent.setAction(detectedTermuxPackage + ".action.RUN_COMMAND");
                intent.putExtra(detectedTermuxPackage + ".extra.COMMAND_PATH", scriptPath);
                intent.putExtra(detectedTermuxPackage + ".extra.SESSION_ID", "daidai-" + System.currentTimeMillis());
                intent.putExtra(detectedTermuxPackage + ".extra.EXTRA_BACKGROUND", false);
                startService(intent);
                Log.d(TAG, "RunCommandService started");
            } catch (Exception e) {
                Log.e(TAG, "RunCommandService failed", e);
                
                // 备用方案：使用 SEND_TERM intent
                try {
                    Intent intent = new Intent("com.termux.service_execute");
                    intent.setPackage(detectedTermuxPackage);
                    intent.putExtra("executablePath", scriptPath);
                    intent.putExtra("arguments", new String[]{});
                    startService(intent);
                    Log.d(TAG, "service_execute started");
                } catch (Exception e2) {
                    Log.e(TAG, "service_execute failed too", e2);
                    Toast.makeText(this, "自动执行失败，请在 Termux 中手动执行:\nbash " + scriptPath, Toast.LENGTH_LONG).show();
                }
            }
            
            // 启动 Termux Activity
            Intent activityIntent = new Intent();
            activityIntent.setClassName(detectedTermuxPackage, detectedTermuxPackage + ".app.TermuxActivity");
            activityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(activityIntent);
            
            Toast.makeText(this, "正在启动面板，请等待...", Toast.LENGTH_LONG).show();
            
            // 延迟打开浏览器
            handler.postDelayed(() -> {
                try {
                    Intent browserIntent = new Intent(Intent.ACTION_VIEW);
                    browserIntent.setData(Uri.parse("http://127.0.0.1:5700"));
                    startActivity(browserIntent);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to open browser", e);
                }
            }, 30000);  // 30秒后打开浏览器
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to start Termux", e);
            Toast.makeText(this, "启动失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            
            // 尝试直接打开 Termux
            try {
                Intent launchIntent = getPackageManager().getLaunchIntentForPackage(detectedTermuxPackage);
                if (launchIntent != null) {
                    startActivity(launchIntent);
                }
            } catch (Exception e2) {
                Log.e(TAG, "Failed to launch Termux directly", e2);
            }
        }
    }
}
