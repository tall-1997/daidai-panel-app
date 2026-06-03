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
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LogOverlayService extends Service {
    private static final String TAG = "LogOverlayService";
    private static final String CHANNEL_ID = "log_overlay_channel";
    private static final int NOTIFICATION_ID = 2;
    
    private WindowManager windowManager;
    private View overlayView;
    private View menuView;
    private WindowManager.LayoutParams params;
    private WindowManager.LayoutParams menuParams;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isMenuShowing = false;

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
        if (menuView != null && windowManager != null) {
            try {
                windowManager.removeView(menuView);
            } catch (Exception e) {
                Log.e(TAG, "Error removing menu", e);
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
            .setContentText("点击悬浮按钮展开菜单")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }

    private void createOverlayButton() {
        int layoutType;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutType = WindowManager.LayoutParams.TYPE_PHONE;
        }

        int sizePx = (int) (48 * getResources().getDisplayMetrics().density);

        params = new WindowManager.LayoutParams(
            sizePx,
            sizePx,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        );
        
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 16;
        params.y = 100;

        // 创建自定义圆形按钮
        overlayView = new View(this) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            
            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                float centerX = getWidth() / 2f;
                float centerY = getHeight() / 2f;
                float radius = Math.min(centerX, centerY) - 2;
                
                // 绘制圆形背景
                paint.setColor(Color.parseColor("#667eea"));
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(centerX, centerY, radius, paint);
                
                // 绘制白色 "L" 字母
                paint.setColor(Color.WHITE);
                paint.setTextSize(radius * 1.2f);
                paint.setTextAlign(Paint.Align.CENTER);
                Paint.FontMetrics fm = paint.getFontMetrics();
                float textY = centerY - (fm.ascent + fm.descent) / 2;
                canvas.drawText("L", centerX, textY, paint);
            }
        };

        // 设置触摸事件
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
                        if (Math.abs(dx) > 5 || Math.abs(dy) > 5) {
                            isMoved = true;
                            params.x = initialX + (int) dx;
                            params.y = initialY + (int) dy;
                            windowManager.updateViewLayout(overlayView, params);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!isMoved) {
                            toggleMenu();
                        }
                        return true;
                }
                return false;
            }
        });

        try {
            windowManager.addView(overlayView, params);
            Log.d(TAG, "Overlay created");
        } catch (Exception e) {
            Log.e(TAG, "Failed to create overlay", e);
        }
    }

    private void toggleMenu() {
        if (isMenuShowing) {
            hideMenu();
        } else {
            showMenu();
        }
    }

    private void showMenu() {
        if (menuView != null) {
            try {
                windowManager.removeView(menuView);
            } catch (Exception e) {
                // ignore
            }
        }

        int layoutType;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutType = WindowManager.LayoutParams.TYPE_PHONE;
        }

        int menuWidth = (int) (200 * getResources().getDisplayMetrics().density);
        
        menuParams = new WindowManager.LayoutParams(
            menuWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        );
        
        menuParams.gravity = Gravity.TOP | Gravity.START;
        menuParams.x = params.x + (int) (56 * getResources().getDisplayMetrics().density);
        menuParams.y = params.y;

        // 创建菜单视图
        LinearLayout menuLayout = new LinearLayout(this);
        menuLayout.setOrientation(LinearLayout.VERTICAL);
        menuLayout.setBackgroundColor(Color.WHITE);
        menuLayout.setElevation(8 * getResources().getDisplayMetrics().density);
        
        // 添加菜单项
        addMenuItem(menuLayout, "导出日志", () -> exportLogs());
        addMenuItem(menuLayout, "安装 Python", () -> installRuntime("python"));
        addMenuItem(menuLayout, "安装 Node.js", () -> installRuntime("node"));
        addMenuItem(menuLayout, "关闭菜单", () -> hideMenu());

        menuView = menuLayout;

        try {
            windowManager.addView(menuView, menuParams);
            isMenuShowing = true;
            Log.d(TAG, "Menu shown");
        } catch (Exception e) {
            Log.e(TAG, "Failed to show menu", e);
        }
    }

    private void hideMenu() {
        if (menuView != null) {
            try {
                windowManager.removeView(menuView);
            } catch (Exception e) {
                // ignore
            }
            menuView = null;
        }
        isMenuShowing = false;
    }

    private void addMenuItem(LinearLayout parent, String text, Runnable action) {
        TextView menuItem = new TextView(this);
        menuItem.setText(text);
        menuItem.setTextSize(14);
        menuItem.setTextColor(Color.parseColor("#333333"));
        int padding = (int) (12 * getResources().getDisplayMetrics().density);
        menuItem.setPadding(padding, padding, padding, padding);
        
        menuItem.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        v.setBackgroundColor(Color.parseColor("#E0E0E0"));
                        return true;
                    case MotionEvent.ACTION_UP:
                        v.setBackgroundColor(Color.TRANSPARENT);
                        action.run();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        v.setBackgroundColor(Color.TRANSPARENT);
                        return true;
                }
                return false;
            }
        });
        
        parent.addView(menuItem);
    }

    private void exportLogs() {
        hideMenu();
        Toast.makeText(this, "正在导出日志...", Toast.LENGTH_SHORT).show();
        
        executor.execute(() -> {
            try {
                String logs = collectLogs();
                
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
                    Toast.makeText(this, "日志已保存: " + fileName, Toast.LENGTH_LONG).show();
                    
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        ClipData clip = ClipData.newPlainText("daidai-log", logs);
                        clipboard.setPrimaryClip(clip);
                        Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show();
                    }
                });
                
                Log.d(TAG, "Logs exported: " + logFile.getAbsolutePath());
                
            } catch (Exception e) {
                Log.e(TAG, "Export failed", e);
                mainHandler.post(() -> {
                    Toast.makeText(this, "导出失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void installRuntime(String name) {
        hideMenu();
        Toast.makeText(this, "正在安装 " + name + "...", Toast.LENGTH_SHORT).show();
        
        executor.execute(() -> {
            try {
                // 获取 JWT token
                String token = getAuthToken();
                if (token == null) {
                    mainHandler.post(() -> {
                        Toast.makeText(this, "请先登录面板", Toast.LENGTH_LONG).show();
                    });
                    return;
                }
                
                // 调用安装 API
                String url = "http://127.0.0.1:5701/api/v1/android-runtime/install";
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Authorization", "Bearer " + token);
                conn.setDoOutput(true);
                
                String body = "{\"name\":\"" + name + "\"}";
                conn.getOutputStream().write(body.getBytes());
                
                int responseCode = conn.getResponseCode();
                Log.d(TAG, "Install API response: " + responseCode);
                
                if (responseCode == 200) {
                    // 读取 SSE 响应
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    String line;
                    StringBuilder result = new StringBuilder();
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("data: ")) {
                            result.append(line.substring(6)).append("\n");
                        }
                    }
                    reader.close();
                    
                    mainHandler.post(() -> {
                        Toast.makeText(this, name + " 安装完成", Toast.LENGTH_LONG).show();
                    });
                } else {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getErrorStream()));
                    String line;
                    StringBuilder error = new StringBuilder();
                    while ((line = reader.readLine()) != null) {
                        error.append(line);
                    }
                    reader.close();
                    
                    mainHandler.post(() -> {
                        Toast.makeText(this, "安装失败: " + error.toString(), Toast.LENGTH_LONG).show();
                    });
                }
                
                conn.disconnect();
                
            } catch (Exception e) {
                Log.e(TAG, "Install failed", e);
                mainHandler.post(() -> {
                    Toast.makeText(this, "安装失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private String getAuthToken() {
        // 从 MainActivity 获取 token
        return MainActivity.authToken;
    }

    private String collectLogs() {
        StringBuilder sb = new StringBuilder();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        
        sb.append("=== 呆呆面板日志导出 ===\n");
        sb.append("导出时间: ").append(sdf.format(new Date())).append("\n");
        sb.append("设备型号: ").append(Build.MODEL).append("\n");
        sb.append("系统版本: Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("应用版本: 0.0.1\n\n");

        // logcat
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
            sb.append("读取失败: ").append(e.getMessage()).append("\n");
        }

        // 面板日志
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
                sb.append("读取失败: ").append(e.getMessage()).append("\n");
            }
        } else {
            sb.append("日志文件不存在\n");
        }

        // 文件检查
        sb.append("\n=== 文件检查 ===\n");
        File webDir = new File(getFilesDir(), "web");
        File indexFile = new File(webDir, "index.html");
        
        sb.append("Web目录: ").append(webDir.exists()).append("\n");
        sb.append("index.html: ").append(indexFile.exists()).append("\n");
        
        File dataDir = new File(getFilesDir(), "Dumb-Panel");
        sb.append("数据目录: ").append(dataDir.exists()).append("\n");
        if (dataDir.exists()) {
            sb.append("config.yaml: ").append(new File(dataDir, "config.yaml").exists()).append("\n");
            sb.append("daidai.db: ").append(new File(dataDir, "daidai.db").exists()).append("\n");
        }

        return sb.toString();
    }
}
