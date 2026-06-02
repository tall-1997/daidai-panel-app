package com.daidai.panel;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

/**
 * 前台服务，用于保持面板服务在后台运行
 */
public class PanelService extends Service {
    private static final String TAG = "PanelService";
    private static final String CHANNEL_ID = "daidai_panel_channel";
    private static final int NOTIFICATION_ID = 1;
    
    private PanelManager panelManager;
    private boolean isRunning = false;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service onCreate");
        
        panelManager = PanelManager.getInstance(this);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Service onStartCommand");
        
        // 启动前台通知
        startForeground(NOTIFICATION_ID, createNotification());
        
        // 启动面板服务器
        if (!isRunning) {
            startPanelServer();
        }
        
        // 如果服务被杀死，自动重启
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Service onDestroy");
        super.onDestroy();
        
        // 停止面板服务器
        if (isRunning) {
            panelManager.stopServer();
            isRunning = false;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.d(TAG, "Service onTaskRemoved");
        super.onTaskRemoved(rootIntent);
        
        // 如果用户滑掉APP，服务继续运行
        // 不在这里停止服务，让服务继续后台运行
    }

    private void startPanelServer() {
        new Thread(() -> {
            try {
                String dataDir = getFilesDir().getAbsolutePath() + "/Dumb-Panel";
                String webDir = getFilesDir().getAbsolutePath() + "/web";
                int port = 5701;
                
                Log.d(TAG, "Starting panel server...");
                Log.d(TAG, "Data dir: " + dataDir);
                Log.d(TAG, "Web dir: " + webDir);
                
                panelManager.startServer(dataDir, webDir, port);
                
                // 等待服务器启动
                int maxWait = 30;
                int waited = 0;
                while (!panelManager.isServerRunning() && waited < maxWait) {
                    Thread.sleep(1000);
                    waited++;
                    Log.d(TAG, "Waiting for server... " + waited + "s");
                }
                
                if (panelManager.isServerRunning()) {
                    isRunning = true;
                    Log.d(TAG, "Panel server started on port " + port);
                    updateNotification("面板服务运行中 - 端口: " + port);
                } else {
                    Log.e(TAG, "Panel server failed to start within timeout");
                    updateNotification("面板服务启动超时");
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Failed to start panel server", e);
                updateNotification("面板服务启动失败: " + e.getMessage());
            }
        }).start();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "呆呆面板服务",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("保持面板服务在后台运行");
            channel.setShowBadge(false);
            
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification createNotification() {
        // 点击通知打开APP
        Intent notificationIntent = new Intent(this, MainActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("呆呆面板")
            .setContentText("正在启动面板服务...")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE);

        return builder.build();
    }

    private void updateNotification(String text) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("呆呆面板")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build();

        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, notification);
        }
    }
}
