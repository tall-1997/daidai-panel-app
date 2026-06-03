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
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
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
    private WindowManager.LayoutParams params;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isOverlayVisible = false;

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
        
        // 注册广播接收器
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
        if (overlayView != null && windowManager != null) {
            try {
                windowManager.removeView(overlayView);
            } catch (Exception e) {
                // ignore
            }
        }
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
                        if (!moved) exportLogs();
                        return true;
                }
                return false;
            }
        });

        try { windowManager.addView(overlayView, params); isOverlayVisible = true; } catch (Exception e) { Log.e(TAG, "addView failed", e); }
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
