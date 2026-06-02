package com.daidai.panel;

import android.content.Context;
import android.util.Log;

/**
 * 面板管理器，负责与Go后端交互
 */
public class PanelManager {
    private static final String TAG = "PanelManager";
    private final Context context;
    private long nativeHandle = 0;
    private boolean isRunning = false;

    public PanelManager(Context context) {
        this.context = context;
        // 加载Go库
        try {
            System.loadLibrary("daidai");
            Log.d(TAG, "Native library loaded");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load native library", e);
        }
    }

    /**
     * 启动面板服务器
     * @param dataDir 数据目录
     * @param webDir 前端资源目录
     * @param port 监听端口
     */
    public void startServer(String dataDir, String webDir, int port) {
        Log.d(TAG, "startServer called");
        
        // 确保目录存在
        new java.io.File(dataDir).mkdirs();
        new java.io.File(webDir).mkdirs();
        
        // 调用Go函数启动服务器
        nativeStartServer(dataDir, webDir, port);
        isRunning = true;
    }

    /**
     * 停止面板服务器
     */
    public void stopServer() {
        Log.d(TAG, "stopServer called");
        nativeStopServer();
        isRunning = false;
    }

    /**
     * 检查服务器是否运行中
     */
    public boolean isServerRunning() {
        return nativeIsServerRunning();
    }

    /**
     * 获取服务器端口
     */
    public int getServerPort() {
        return nativeGetServerPort();
    }

    /**
     * 获取服务器URL
     */
    public String getServerURL() {
        int port = getServerPort();
        if (port > 0) {
            return "http://127.0.0.1:" + port;
        }
        return "http://127.0.0.1:5701";
    }

    /**
     * 初始化数据目录
     */
    public void initDataDir(String dataDir) {
        new java.io.File(dataDir + "/scripts").mkdirs();
        new java.io.File(dataDir + "/logs").mkdirs();
        new java.io.File(dataDir + "/backups").mkdirs();
        new java.io.File(dataDir + "/deps").mkdirs();
    }

    /**
     * 初始化Web目录
     */
    public void initWebDir(String webDir) {
        new java.io.File(webDir).mkdirs();
    }

    /**
     * 获取版本号
     */
    public String getVersion() {
        return "2.2.14-mobile";
    }

    // Native方法声明
    private native void nativeStartServer(String dataDir, String webDir, int port);
    private native void nativeStopServer();
    private native boolean nativeIsServerRunning();
    private native int nativeGetServerPort();
}
