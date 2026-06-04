package com.termux.daidai;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 呆呆面板初始化管理器
 * 负责在首次启动时解压资源并初始化面板
 */
public class DaidaiInitializer {
    private static final String TAG = "DaidaiInitializer";
    private static final String PREF_NAME = "daidai_prefs";
    private static final String KEY_INITIALIZED = "initialized";
    
    private Context context;
    private File dataDir;
    
    public DaidaiInitializer(Context context) {
        this.context = context;
        this.dataDir = new File(context.getFilesDir().getParentFile(), "files/home");
    }
    
    /**
     * 检查是否已初始化
     */
    public boolean isInitialized() {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_INITIALIZED, false);
    }
    
    /**
     * 执行初始化
     */
    public boolean initialize() {
        if (isInitialized()) {
            Log.d(TAG, "Already initialized");
            return true;
        }
        
        try {
            Log.d(TAG, "Starting initialization...");
            
            // 创建目录
            File daidaiDir = new File("/opt/daidai-panel");
            daidaiDir.mkdirs();
            
            // 解压资源
            extractAssets();
            
            // 创建启动脚本
            createStartScript();
            
            // 标记初始化完成
            SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            prefs.edit().putBoolean(KEY_INITIALIZED, true).apply();
            
            Log.d(TAG, "Initialization completed");
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "Initialization failed", e);
            return false;
        }
    }
    
    /**
     * 解压资源文件
     */
    private void extractAssets() throws IOException {
        String[] assets = {"daidai-server-arm64", "config.yaml", "init.sh", "start.sh", "stop.sh"};
        
        for (String asset : assets) {
            extractAsset("daidai/" + asset, new File("/opt/daidai-panel", asset));
        }
        
        // 解压前端资源
        extractAssetDir("daidai/web", new File("/opt/daidai-panel/web"));
    }
    
    /**
     * 解压单个资源文件
     */
    private void extractAsset(String assetPath, File destFile) throws IOException {
        if (destFile.exists()) {
            return;
        }
        
        Log.d(TAG, "Extracting: " + assetPath);
        
        InputStream in = context.getAssets().open(assetPath);
        FileOutputStream out = new FileOutputStream(destFile);
        
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        
        out.flush();
        out.close();
        in.close();
        
        // 设置执行权限
        if (assetPath.endsWith(".sh") || assetPath.contains("daidai-server")) {
            destFile.setExecutable(true, false);
        }
    }
    
    /**
     * 解压目录
     */
    private void extractAssetDir(String assetDir, File destDir) throws IOException {
        destDir.mkdirs();
        
        String[] files = context.getAssets().list("daidai/" + assetDir);
        if (files != null) {
            for (String file : files) {
                File destFile = new File(destDir, file);
                extractAsset("daidai/" + assetDir + "/" + file, destFile);
            }
        }
    }
    
    /**
     * 创建启动脚本
     */
    private void createStartScript() throws IOException {
        File scriptFile = new File(dataDir, "daidai-init.sh");
        
        String script = "#!/data/data/com.termux/files/usr/bin/bash\n" +
            "# 呆呆面板自动初始化\n" +
            "if [ -f /opt/daidai-panel/daidai-server ]; then\n" +
            "    if ! pgrep -f \"daidai-server\" > /dev/null 2>&1; then\n" +
            "        cd /opt/daidai-panel\n" +
            "        nohup ./daidai-server > daidai.log 2>&1 &\n" +
            "        sleep 2\n" +
            "        if pgrep -f \"daidai-server\" > /dev/null 2>&1; then\n" +
            "            echo \"呆呆面板已启动\"\n" +
            "            echo \"访问地址: http://127.0.0.1:5700\"\n" +
            "        fi\n" +
            "    fi\n" +
            "fi\n";
        
        FileOutputStream out = new FileOutputStream(scriptFile);
        out.write(script.getBytes());
        out.flush();
        out.close();
        
        scriptFile.setExecutable(true, false);
    }
    
    /**
     * 获取面板访问地址
     */
    public String getPanelUrl() {
        return "http://127.0.0.1:5700";
    }
}
