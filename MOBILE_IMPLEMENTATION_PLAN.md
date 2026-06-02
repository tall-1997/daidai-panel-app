# 呆呆面板移动端双端开发实施方案

## 项目概述

将呆呆面板（daidai-panel）打包为Android和iOS原生APP，实现"安装即用"的体验。

## 技术栈确认

### 呆呆面板原始技术栈
- **后端**: Go 1.25 + Gin + GORM + SQLite（glebarez/sqlite，纯Go实现，CGO_ENABLED=0）
- **前端**: Vue 3 + TypeScript + Element Plus + Vite + Monaco Editor
- **数据库**: SQLite（纯Go实现，无外部依赖）

### 移动端技术选型
| 平台 | 技术方案 | 最低版本 | CPU架构 |
|------|----------|----------|---------|
| Android | 原生Java/Kotlin + WebView | Android 9 (API 28) | arm64-v8a, armeabi-v7a |
| iOS | Swift + WKWebView + gomobile | iOS 16 | arm64 |

## 架构设计

```
┌─────────────────────────────────────────────────────────────┐
│                        用户设备                              │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  ┌───────────────┐                    ┌───────────────┐    │
│  │   Android APP │                    │    iOS APP    │    │
│  │   (APK)       │                    │    (IPA)      │    │
│  └───────┬───────┘                    └───────┬───────┘    │
│          │                                    │            │
│  ┌───────▼───────┐                    ┌───────▼───────┐    │
│  │  Go Binary    │                    │ Go Framework  │    │
│  │  (arm64/armv7)│                    │ (.xcframework)│    │
│  │  直接执行     │                    │  gomobile绑定 │    │
│  └───────┬───────┘                    └───────┬───────┘    │
│          │                                    │            │
│  ┌───────▼───────┐                    ┌───────▼───────┐    │
│  │   WebView     │                    │   WKWebView   │    │
│  │  127.0.0.1    │◄──────────────────►│  127.0.0.1    │    │
│  │   :5701       │    HTTP请求        │   :5701       │    │
│  └───────────────┘                    └───────────────┘    │
│                                                             │
│  ┌─────────────────────────────────────────────────────┐   │
│  │              前端静态资源 (Vue3 Build)               │   │
│  │        index.html │ assets/ │ monaco/ │ ...         │   │
│  └─────────────────────────────────────────────────────┘   │
│                                                             │
│  ┌─────────────────────────────────────────────────────┐   │
│  │                    数据目录                          │   │
│  │  daidai.db │ scripts/ │ logs/ │ backups/ │ deps/   │   │
│  └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

## 实施阶段

### 阶段一：Go后端改造（已完成）

#### 1.1 创建移动端API接口包
- 文件：`server/mobile/mobile.go`
- 功能：
  - `StartServer(dataDir, webDir, port)` - 启动服务器
  - `StopServer()` - 停止服务器
  - `GetServerState()` - 获取服务器状态
  - `IsServerRunning()` - 检查运行状态

#### 1.2 创建gomobile绑定接口
- 文件：`server/mobile/binding.go`
- 导出类：`DaidaiPanel`
- 导出方法：
  - `StartServer(dataDir, webDir, port) string`
  - `StopServer() string`
  - `GetServerState() string`
  - `IsServerRunning() bool`
  - `GetServerPort() int`
  - `GetServerURL() string`
  - `InitDataDir(dataDir) string`
  - `GetVersion() string`

#### 1.3 创建构建脚本
- 文件：`build.sh`
- 支持平台：Android (arm64, arm), iOS (arm64), Desktop

### 阶段二：Android原生APP开发

#### 2.1 创建Android工程
```
android/
├── app/
│   ├── src/main/
│   │   ├── java/com/daidai/panel/
│   │   │   ├── MainActivity.java
│   │   │   ├── PanelService.java
│   │   │   ├── BootReceiver.java
│   │   │   └── PanelManager.java
│   │   ├── res/
│   │   │   ├── layout/
│   │   │   │   └── activity_main.xml
│   │   │   ├── values/
│   │   │   │   ├── strings.xml
│   │   │   │   └── styles.xml
│   │   │   └── drawable/
│   │   └── AndroidManifest.xml
│   └── build.gradle
├── gradle/
├── build.gradle
├── settings.gradle
└── gradle.properties
```

#### 2.2 核心功能实现

**MainActivity.java**
```java
public class MainActivity extends AppCompatActivity {
    private WebView webView;
    private PanelManager panelManager;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        // 初始化WebView
        webView = findViewById(R.id.webview);
        setupWebView();
        
        // 启动面板服务
        panelManager = new PanelManager(this);
        panelManager.startServer();
        
        // 等待服务启动后加载页面
        waitForServerAndLoad();
    }
    
    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        
        // 允许混合内容（HTTP在本地）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
    }
    
    private void waitForServerAndLoad() {
        new Thread(() -> {
            while (!panelManager.isServerRunning()) {
                try { Thread.sleep(100); } catch (InterruptedException e) {}
            }
            runOnUiThread(() -> {
                webView.loadUrl("http://127.0.0.1:5701");
            });
        }).start();
    }
}
```

**PanelService.java (ForegroundService)**
```java
public class PanelService extends Service {
    private static final String CHANNEL_ID = "daidai_panel_channel";
    private static final int NOTIFICATION_ID = 1;
    private PanelManager panelManager;
    
    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        panelManager = new PanelManager(this);
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, createNotification());
        panelManager.startServer();
        return START_STICKY;
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        panelManager.stopServer();
    }
    
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "呆呆面板服务",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("保持面板服务运行");
            
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }
    
    private Notification createNotification() {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("呆呆面板")
            .setContentText("面板服务运行中")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW);
        
        return builder.build();
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
```

**BootReceiver.java (开机自启)**
```java
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Intent serviceIntent = new Intent(context, PanelService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
        }
    }
}
```

**AndroidManifest.xml 关键配置**
```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.daidai.panel">
    
    <!-- 权限 -->
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    
    <application
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:theme="@style/AppTheme">
        
        <!-- 主Activity -->
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:launchMode="singleTask">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
        
        <!-- 前台服务 -->
        <service
            android:name=".PanelService"
            android:foregroundServiceType="specialUse"
            android:exported="false" />
        
        <!-- 开机自启广播 -->
        <receiver
            android:name=".BootReceiver"
            android:exported="true"
            android:enabled="true">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
            </intent-filter>
        </receiver>
        
    </application>
</manifest>
```

### 阶段三：iOS原生APP开发

#### 3.1 创建iOS工程
```
ios/
├── DaidaiPanel/
│   ├── AppDelegate.swift
│   ├── SceneDelegate.swift
│   ├── ViewController.swift
│   ├── PanelManager.swift
│   ├── PanelWebView.swift
│   ├── Info.plist
│   ├── Assets.xcassets/
│   └── LaunchScreen.storyboard
├── DaidaiPanel.xcodeproj/
└── Podfile (或 Package.swift)
```

#### 3.2 核心功能实现

**PanelManager.swift**
```swift
import Foundation
import DaidaiPanel // gomobile生成的框架

class PanelManager {
    private var panel: DaidaiPanelDaidaiPanel?
    private var isRunning = false
    
    func startServer(dataDir: String, webDir: String, port: Int = 5701) {
        panel = DaidaiPanelNewDaidaiPanel()
        
        // 初始化数据目录
        let initResult = panel?.initDataDir(dataDir)
        print("Init data dir: \(initResult ?? "")")
        
        // 启动服务器
        let result = panel?.startServer(dataDir, webDir: webDir, port: Int(port))
        print("Start server: \(result ?? "")")
        
        isRunning = panel?.isServerRunning() ?? false
    }
    
    func stopServer() {
        let result = panel?.stopServer()
        print("Stop server: \(result ?? "")")
        isRunning = false
    }
    
    func isServerRunning() -> Bool {
        return panel?.isServerRunning() ?? false
    }
    
    func getServerURL() -> String {
        return panel?.getServerURL() ?? "http://127.0.0.1:5701"
    }
}
```

**ViewController.swift**
```swift
import UIKit
import WebKit

class ViewController: UIViewController {
    private var webView: WKWebView!
    private let panelManager = PanelManager()
    
    override func viewDidLoad() {
        super.viewDidLoad()
        
        setupWebView()
        startPanelServer()
    }
    
    private func setupWebView() {
        let configuration = WKWebViewConfiguration()
        configuration.preferences.javaScriptEnabled = true
        
        // 允许本地HTTP访问
        configuration.preferences.setValue(true, forKey: "allowFileAccessFromFileURLs")
        
        webView = WKWebView(frame: view.bounds, configuration: configuration)
        webView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(webView)
    }
    
    private func startPanelServer() {
        let dataDir = getDocumentsDirectory().appendingPathComponent("Dumb-Panel").path
        let webDir = Bundle.main.resourcePath!.appending("/web")
        
        // 在后台线程启动服务器
        DispatchQueue.global(qos: .background).async {
            self.panelManager.startServer(dataDir: dataDir, webDir: webDir, port: 5701)
            
            // 等待服务器启动
            while !self.panelManager.isServerRunning() {
                Thread.sleep(forTimeInterval: 0.1)
            }
            
            // 在主线程加载WebView
            DispatchQueue.main.async {
                let url = URL(string: self.panelManager.getServerURL())!
                self.webView.load(URLRequest(url: url))
            }
        }
    }
    
    private func getDocumentsDirectory() -> URL {
        let paths = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)
        return paths[0]
    }
}
```

**Info.plist 关键配置**
```xml
<key>NSAppTransportSecurity</key>
<dict>
    <key>NSAllowsLocalNetworking</key>
    <true/>
</dict>
<key>UIBackgroundModes</key>
<array>
    <string>fetch</string>
    <string>processing</string>
</array>
```

### 阶段四：数据同步功能

#### 4.1 同步协议设计

支持两种同步方式：
1. **WebDAV同步** - 通用方案，支持各种NAS和云存储
2. **iCloud同步** (仅iOS) - 苹果原生方案

#### 4.2 同步数据结构
```json
{
  "version": "1.0",
  "timestamp": 1717267200,
  "device_id": "device-uuid",
  "data": {
    "tasks": [...],
    "env_vars": [...],
    "subscriptions": [...],
    "notify_channels": [...]
  }
}
```

#### 4.3 同步API接口
```go
// 在Go后端添加同步API
router.POST("/api/sync/export", handleSyncExport)
router.POST("/api/sync/import", handleSyncImport)
router.GET("/api/sync/status", handleSyncStatus)
```

## 构建流程

### Android构建
```bash
# 1. 构建Go二进制
cd server
CGO_ENABLED=0 GOOS=android GOARCH=arm64 go build -o ../android/app/src/main/jniLibs/arm64-v8a/libdaidai.so .
CGO_ENABLED=0 GOOS=android GOARCH=arm go build -o ../android/app/src/main/jniLibs/armeabi-v7a/libdaidai.so .

# 2. 构建前端
cd web
npm run build
cp -r dist/* ../android/app/src/main/assets/web/

# 3. 构建APK
cd ../android
./gradlew assembleRelease
```

### iOS构建
```bash
# 1. 构建gomobile框架
cd server
gomobile bind -target=ios -iosversion=16.0 -o ../ios/Frameworks/DaidaiPanel.xcframework ./mobile

# 2. 构建前端
cd web
npm run build
cp -r dist/* ../ios/DaidaiPanel/Resources/web/

# 3. 构建IPA
cd ../ios
xcodebuild -scheme DaidaiPanel -configuration Release
```

## 数据目录结构

### Android
```
/data/data/com.daidai.panel/files/
├── Dumb-Panel/
│   ├── daidai.db
│   ├── .jwt_secret
│   ├── panel.log
│   ├── scripts/
│   ├── logs/
│   ├── backups/
│   └── deps/
└── config.yaml
```

### iOS
```
Documents/
├── Dumb-Panel/
│   ├── daidai.db
│   ├── .jwt_secret
│   ├── panel.log
│   ├── scripts/
│   ├── logs/
│   ├── backups/
│   └── deps/
└── config.yaml
```

## 注意事项

### Android
1. **后台保活**: 使用ForegroundService + 通知栏常驻
2. **开机自启**: 需要用户手动授权自启动权限
3. **电池优化**: 引导用户关闭电池优化，避免进程被杀
4. **端口限制**: Android 10+ 限制后台应用监听端口，需要前台服务

### iOS
1. **后台限制**: iOS会暂停后台进程，需要使用Background Modes
2. **内存限制**: 后台应用内存限制严格，可能被系统杀死
3. **网络限制**: 后台网络请求受限，需要申请后台网络权限
4. **gomobile限制**: 无法直接执行Go二进制，必须通过gomobile绑定

### 通用
1. **端口冲突**: 使用127.0.0.1本地回环，避免与其他应用冲突
2. **数据安全**: SQLite数据库存储在APP私有目录，卸载即丢失
3. **版本兼容**: 确保Go后端和前端版本匹配

## 下一步行动

1. ✅ 阶段一：Go后端改造 - 已完成
2. 🔄 阶段二：Android原生APP开发 - 进行中
3. ⏳ 阶段三：iOS原生APP开发 - 待开始
4. ⏳ 阶段四：数据同步功能 - 待开始
