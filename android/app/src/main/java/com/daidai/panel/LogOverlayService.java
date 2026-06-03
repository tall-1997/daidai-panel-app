package com.daidai.panel;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 日志悬浮按钮服务
 */
public class LogOverlayService extends Service {
    private static final String TAG = "LogOverlayService";
    private static final String CHANNEL_ID = "log_overlay_channel";
    private static final int NOTIFICATION_ID = 2;
    
    private WindowManager windowManager;
    private View overlayView;
    private WindowManager.LayoutParams params;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate");
        
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());
        createOverlayButton();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (overlayView != null && windowManager != null) {
            try {
                windowManager.removeView(overlayView);
            } catch (Exception e) {
                Log.e(TAG, "Error removing overlay", e);
            }
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "日志工具",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("日志导出悬浮按钮");
            
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("日志工具运行中")
            .setContentText("点击悬浮按钮导出日志")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }

    private void createOverlayButton() {
        // 设置悬浮窗口参数
        int layoutType;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutType = WindowManager.LayoutParams.TYPE_PHONE;
        }

        params = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        );
        
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 0;
        params.y = 200;

        // 创建悬浮按钮视图
        overlayView = new View(this);
        overlayView.setBackgroundResource(android.R.drawable.ic_dialog_info);
        overlayView.setAlpha(0.8f);
        overlayView.setScaleX(0.7f);
        overlayView.setScaleY(0.7f);

        // 设置触摸事件 - 拖动和点击
        overlayView.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float initialTouchX, initialTouchY;
            private boolean isMoved = false;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        isMoved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - initialTouchX;
                        float dy = event.getRawY() - initialTouchY;
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            isMoved = true;
                            params.x = initialX + (int) dx;
                            params.y = initialY + (int) dy;
                            windowManager.updateViewLayout(overlayView, params);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!isMoved) {
                            // 点击事件 - 导出日志
                            exportLogs();
                        }
                        return true;
                }
                return false;
            }
        });

        try {
            windowManager.addView(overlayView, params);
            Log.d(TAG, "Overlay button created");
        } catch (Exception e) {
            Log.e(TAG, "Failed to create overlay", e);
        }
    }

    private void exportLogs() {
        Toast.makeText(this, "正在导出日志...", Toast.LENGTH_SHORT).show();
        
        executor.execute(() -> {
            try {
                // 收集日志
                String logs = collectLogs();
                
                // 保存到文件
                String fileName = "daidai-log-" + 
                    new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(new Date()) + ".txt";
                
                File logDir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOCUMENTS), "DaidaiPanel");
                if (!logDir.exists()) {
                    logDir.mkdirs();
                }
                
                File logFile = new File(logDir, fileName);
                FileOutputStream fos = new FileOutputStream(logFile);
                fos.write(logs.getBytes());
                fos.flush();
                fos.close();
                
                mainHandler.post(() -> {
                    Toast.makeText(this, "日志已保存到: " + logFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
                    
                    // 复制到剪贴板
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        ClipData clip = ClipData.newPlainText("daidai-log", logs);
                        clipboard.setPrimaryClip(clip);
                        Toast.makeText(this, "日志已复制到剪贴板", Toast.LENGTH_SHORT).show();
                    }
                });
                
                Log.d(TAG, "Logs exported to: " + logFile.getAbsolutePath());
                
            } catch (Exception e) {
                Log.e(TAG, "Failed to export logs", e);
                mainHandler.post(() -> {
                    Toast.makeText(this, "导出日志失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private String collectLogs() {
        StringBuilder sb = new StringBuilder();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        
        sb.append("=== 呆呆面板日志导出 ===\n");
        sb.append("导出时间: ").append(sdf.format(new Date())).append("\n");
        sb.append("设备型号: ").append(Build.MODEL).append("\n");
        sb.append("系统版本: Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("应用版本: 0.0.1\n\n");

        // 读取 logcat 日志
        sb.append("=== 应用日志 (logcat) ===\n");
        try {
            Process process = Runtime.getRuntime().exec("logcat -d -s PanelManager:* MainActivity:* PanelService:*");
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            int lineCount = 0;
            while ((line = reader.readLine()) != null && lineCount < 1000) {
                sb.append(line).append("\n");
                lineCount++;
            }
            reader.close();
        } catch (IOException e) {
            sb.append("读取 logcat 失败: ").append(e.getMessage()).append("\n");
        }

        // 读取面板日志文件
        sb.append("\n=== 面板服务日志 ===\n");
        File panelLog = new File(getFilesDir(), "Dumb-Panel/panel.log");
        if (panelLog.exists()) {
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(panelLog)));
                String line;
                int lineCount = 0;
                while ((line = reader.readLine()) != null && lineCount < 500) {
                    sb.append(line).append("\n");
                    lineCount++;
                }
                reader.close();
            } catch (IOException e) {
                sb.append("读取面板日志失败: ").append(e.getMessage()).append("\n");
            }
        } else {
            sb.append("面板日志文件不存在\n");
        }

        // 检查关键文件
        sb.append("\n=== 文件检查 ===\n");
        File webDir = new File(getFilesDir(), "web");
        File indexFile = new File(webDir, "index.html");
        File binDir = new File(getFilesDir(), "bin");
        File binary = new File(binDir, "daidai-server");
        
        sb.append("Web目录存在: ").append(webDir.exists()).append("\n");
        sb.append("index.html存在: ").append(indexFile.exists()).append("\n");
        sb.append("Bin目录存在: ").append(binDir.exists()).append("\n");
        sb.append("二进制文件存在: ").append(binary.exists()).append("\n");
        if (binary.exists()) {
            sb.append("二进制文件大小: ").append(binary.length()).append(" bytes\n");
            sb.append("二进制可执行: ").append(binary.canExecute()).append("\n");
        }
        
        // 检查数据目录
        File dataDir = new File(getFilesDir(), "Dumb-Panel");
        sb.append("数据目录存在: ").append(dataDir.exists()).append("\n");
        if (dataDir.exists()) {
            File configFile = new File(dataDir, "config.yaml");
            sb.append("配置文件存在: ").append(configFile.exists()).append("\n");
            File dbFile = new File(dataDir, "daidai.db");
            sb.append("数据库存在: ").append(dbFile.exists()).append("\n");
        }

        return sb.toString();
    }
}
