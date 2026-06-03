package com.daidai.panel;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
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
    private static final int BUTTON_SIZE_DP = 44;

    private WindowManager windowManager;
    private View overlayView;
    private View menuView;
    private WindowManager.LayoutParams params;
    private WindowManager.LayoutParams menuParams;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isOverlayVisible = false;
    private boolean isMenuShowing = false;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("com.daidai.panel.SHOW_OVERLAY".equals(intent.getAction())) {
                showOverlay();
            } else if ("com.daidai.panel.HIDE_OVERLAY".equals(intent.getAction())) {
                hideOverlay();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());
        createOverlayButton();
        
        IntentFilter filter = new IntentFilter();
        filter.addAction("com.daidai.panel.SHOW_OVERLAY");
        filter.addAction("com.daidai.panel.HIDE_OVERLAY");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }
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
        try { unregisterReceiver(receiver); } catch (Exception e) { /* ignore */ }
        hideOverlay();
        hideMenu();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "日志工具", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("日志导出悬浮按钮");
            NotificationManager mgr = getSystemService(NotificationManager.class);
            if (mgr != null) mgr.createNotificationChannel(channel);
        }
    }

    private Notification createNotification() {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
            new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("日志工具运行中")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
            .build();
    }

    private void createOverlayButton() {
        int sizePx = (int) (BUTTON_SIZE_DP * getResources().getDisplayMetrics().density);

        int layoutType;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutType = WindowManager.LayoutParams.TYPE_PHONE;
        }

        params = new WindowManager.LayoutParams(
            sizePx, sizePx, layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 12;
        params.y = 120;

        overlayView = new View(this) {
            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                float cx = getWidth() / 2f, cy = getHeight() / 2f;
                float r = Math.min(cx, cy) - 2;
                p.setColor(Color.parseColor("#6366F1"));
                canvas.drawCircle(cx, cy, r, p);
                p.setColor(Color.WHITE);
                p.setTextSize(r * 1.1f);
                p.setTextAlign(Paint.Align.CENTER);
                Paint.FontMetrics fm = p.getFontMetrics();
                canvas.drawText("L", cx, cy - (fm.ascent + fm.descent) / 2, p);
            }
        };

        overlayView.setOnTouchListener(new View.OnTouchListener() {
            int startX, startY, startTouchX, startTouchY;
            boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = params.x; startY = params.y;
                        startTouchX = (int) e.getRawX(); startTouchY = (int) e.getRawY();
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (Math.abs(e.getRawX() - startTouchX) > 5 || Math.abs(e.getRawY() - startTouchY) > 5) {
                            moved = true;
                            params.x = startX + (int)(e.getRawX() - startTouchX);
                            params.y = startY + (int)(e.getRawY() - startTouchY);
                            windowManager.updateViewLayout(overlayView, params);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) toggleMenu();
                        return true;
                }
                return false;
            }
        });

        showOverlay();
    }

    private void toggleMenu() {
        if (isMenuShowing) {
            hideMenu();
        } else {
            showMenu();
        }
    }

    private void showMenu() {
        hideMenu();

        int layoutType;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutType = WindowManager.LayoutParams.TYPE_PHONE;
        }

        int menuWidth = (int) (200 * getResources().getDisplayMetrics().density);
        
        menuParams = new WindowManager.LayoutParams(
            menuWidth, WindowManager.LayoutParams.WRAP_CONTENT, layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT);
        menuParams.gravity = Gravity.TOP | Gravity.START;
        menuParams.x = params.x + (int) (56 * getResources().getDisplayMetrics().density);
        menuParams.y = params.y;

        LinearLayout menuLayout = new LinearLayout(this);
        menuLayout.setOrientation(LinearLayout.VERTICAL);
        menuLayout.setBackgroundColor(Color.WHITE);
        menuLayout.setElevation(8 * getResources().getDisplayMetrics().density);
        
        addMenuItem(menuLayout, "导出日志", () -> exportLogs());
        addMenuItem(menuLayout, "安装 Python", () -> installRuntime("python"));
        addMenuItem(menuLayout, "安装 Node.js", () -> installRuntime("node"));
        addMenuItem(menuLayout, "关闭菜单", () -> hideMenu());

        menuView = menuLayout;

        try {
            windowManager.addView(menuView, menuParams);
            isMenuShowing = true;
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

    private void showOverlay() {
        if (overlayView != null && !isOverlayVisible) {
            try {
                windowManager.addView(overlayView, params);
                isOverlayVisible = true;
            } catch (Exception e) {
                Log.e(TAG, "showOverlay failed", e);
            }
        }
    }

    private void hideOverlay() {
        hideMenu();
        if (overlayView != null && isOverlayVisible) {
            try {
                windowManager.removeView(overlayView);
                isOverlayVisible = false;
            } catch (Exception e) {
                Log.e(TAG, "hideOverlay failed", e);
            }
        }
    }

    private void exportLogs() {
        hideMenu();
        Toast.makeText(this, "正在导出日志...", Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            try {
                String logs = collectLogs();
                String fileName = "daidai-log-" +
                    new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(new Date()) + ".txt";
                File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOCUMENTS), "DaidaiPanel");
                if (!dir.exists()) dir.mkdirs();
                File f = new File(dir, fileName);
                FileOutputStream fos = new FileOutputStream(f);
                fos.write(logs.getBytes());
                fos.flush(); fos.close();

                mainHandler.post(() -> {
                    Toast.makeText(this, "日志已保存: " + fileName, Toast.LENGTH_LONG).show();
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null) { cm.setPrimaryClip(ClipData.newPlainText("log", logs)); }
                });
            } catch (Exception e) {
                mainHandler.post(() -> Toast.makeText(this, "导出失败: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void installRuntime(String name) {
        hideMenu();
        Toast.makeText(this, "正在安装 " + name + "...", Toast.LENGTH_SHORT).show();
        
        executor.execute(() -> {
            try {
                String token = MainActivity.authToken;
                if (token == null) {
                    mainHandler.post(() -> Toast.makeText(this, "请先登录面板", Toast.LENGTH_LONG).show());
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
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("data: ")) {
                            String msg = line.substring(6);
                            mainHandler.post(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
                        }
                    }
                    reader.close();
                    mainHandler.post(() -> Toast.makeText(this, name + " 安装完成", Toast.LENGTH_LONG).show());
                } else {
                    mainHandler.post(() -> Toast.makeText(this, "安装失败: HTTP " + responseCode, Toast.LENGTH_LONG).show());
                }
                
                conn.disconnect();
                
            } catch (Exception e) {
                Log.e(TAG, "Install failed", e);
                mainHandler.post(() -> Toast.makeText(this, "安装失败: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private String collectLogs() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== 呆呆面板日志导出 ===\n");
        sb.append("导出时间: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date())).append("\n");
        sb.append("设备型号: ").append(Build.MODEL).append("\n");
        sb.append("系统版本: Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n\n");

        sb.append("=== 应用日志 ===\n");
        try {
            Process p = Runtime.getRuntime().exec("logcat -d -s PanelManager:* MainActivity:* PanelService:*");
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line; int n = 0;
            while ((line = r.readLine()) != null && n++ < 1000) sb.append(line).append("\n");
            r.close();
        } catch (IOException e) { sb.append("读取失败: ").append(e.getMessage()).append("\n"); }

        sb.append("\n=== 面板服务日志 ===\n");
        File log = new File(getFilesDir(), "Dumb-Panel/panel.log");
        if (log.exists()) {
            try {
                BufferedReader r = new BufferedReader(new InputStreamReader(new java.io.FileInputStream(log)));
                String line; int n = 0;
                while ((line = r.readLine()) != null && n++ < 500) sb.append(line).append("\n");
                r.close();
            } catch (IOException e) { sb.append("读取失败\n"); }
        } else { sb.append("日志文件不存在\n"); }

        sb.append("\n=== 文件检查 ===\n");
        sb.append("Web目录: ").append(new File(getFilesDir(), "web").exists()).append("\n");
        sb.append("index.html: ").append(new File(getFilesDir(), "web/index.html").exists()).append("\n");
        File bin = new File(getFilesDir(), "bin/daidai-server");
        sb.append("二进制文件: ").append(bin.exists()).append("\n");
        if (bin.exists()) sb.append("大小: ").append(bin.length()).append("\n");

        return sb.toString();
    }
}
