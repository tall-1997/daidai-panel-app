import Foundation
import UIKit

/// 面板管理器，负责管理Go后端服务
class PanelManager {
    
    // MARK: - Singleton
    
    static let shared = PanelManager()
    
    // MARK: - Properties
    
    private var panel: DaidaiPanel?
    private var isRunning = false
    private var port: Int = 5701
    private var dataDir: String = ""
    private var webDir: String = ""
    
    private var backgroundTask: UIBackgroundTaskIdentifier = .invalid
    
    // MARK: - Initialization
    
    private init() {
        // 初始化面板实例
        panel = DaidaiPanel()
    }
    
    // MARK: - Public Methods
    
    /// 启动服务器
    func startServer(dataDir: String, webDir: String, port: Int = 5701) {
        self.dataDir = dataDir
        self.webDir = webDir
        self.port = port
        
        guard let panel = panel else {
            print("[PanelManager] Panel instance not initialized")
            return
        }
        
        print("[PanelManager] Starting server...")
        print("[PanelManager] Data dir: \(dataDir)")
        print("[PanelManager] Web dir: \(webDir)")
        print("[PanelManager] Port: \(port)")
        
        let result = panel.startServer(dataDir, webDir: webDir, port: Int(port))
        print("[PanelManager] Start result: \(result)")
        
        isRunning = panel.isServerRunning()
    }
    
    /// 停止服务器
    func stopServer() {
        guard let panel = panel else { return }
        
        print("[PanelManager] Stopping server...")
        let result = panel.stopServer()
        print("[PanelManager] Stop result: \(result)")
        
        isRunning = false
    }
    
    /// 检查服务器是否运行中
    func isServerRunning() -> Bool {
        guard let panel = panel else { return false }
        return panel.isServerRunning()
    }
    
    /// 获取服务器URL
    func getServerURL() -> String {
        guard let panel = panel else { return "http://127.0.0.1:5701" }
        return panel.getServerURL()
    }
    
    /// 获取服务器端口
    func getServerPort() -> Int {
        guard let panel = panel else { return port }
        return Int(panel.getServerPort())
    }
    
    /// 初始化数据目录
    func initDataDir(_ dir: String) {
        dataDir = dir
        
        let fileManager = FileManager.default
        let subDirs = ["scripts", "logs", "backups", "deps"]
        
        for subDir in subDirs {
            let path = (dir as NSString).appendingPathComponent(subDir)
            try? fileManager.createDirectory(atPath: path, withIntermediateDirectories: true, attributes: nil)
        }
        
        print("[PanelManager] Data dir initialized: \(dir)")
    }
    
    /// 初始化Web目录
    func initWebDir(_ dir: String) {
        webDir = dir
        print("[PanelManager] Web dir initialized: \(dir)")
    }
    
    /// 获取版本号
    func getVersion() -> String {
        return "2.2.14-mobile"
    }
    
    /// 确保服务在后台继续运行
    func ensureBackgroundRunning() {
        guard isRunning else { return }
        
        // 申请后台任务
        backgroundTask = UIApplication.shared.beginBackgroundTask { [weak self] in
            guard let self = self else { return }
            
            // 后台任务即将结束，结束任务
            if self.backgroundTask != .invalid {
                UIApplication.shared.endBackgroundTask(self.backgroundTask)
                self.backgroundTask = .invalid
            }
        }
        
        // 在后台执行一些操作
        DispatchQueue.global(qos: .background).async { [weak self] in
            guard let self = self else { return }
            
            // 检查服务是否还在运行
            if !self.isServerRunning() {
                // 重新启动服务
                self.startServerIfNeeded()
            }
            
            // 结束后台任务
            DispatchQueue.main.async {
                if self.backgroundTask != .invalid {
                    UIApplication.shared.endBackgroundTask(self.backgroundTask)
                    self.backgroundTask = .invalid
                }
            }
        }
    }
    
    /// 如果需要，启动服务器
    func startServerIfNeeded() {
        guard !isRunning else { return }
        
        let dataDir = getDocumentsDirectory().appendingPathComponent("Dumb-Panel").path
        let webDir = Bundle.main.resourcePath!.appending("/web")
        
        // 初始化目录
        initDataDir(dataDir)
        initWebDir(webDir)
        
        // 启动服务器
        startServer(dataDir: dataDir, webDir: webDir, port: port)
    }
    
    // MARK: - Private Methods
    
    private func getDocumentsDirectory() -> URL {
        let paths = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)
        return paths[0]
    }
}

// MARK: - DaidaiPanel Class

/// DaidaiPanel Go框架的Swift封装
/// 注意：这个类会在gomobile bind后自动生成，这里只是占位
class DaidaiPanel {
    
    func startServer(_ dataDir: String, webDir: String, port: Int) -> String {
        // TODO: 调用Go函数
        // 在gomobile bind后，这里会调用实际的Go函数
        return "{\"success\":true}"
    }
    
    func stopServer() -> String {
        // TODO: 调用Go函数
        return "{\"success\":true}"
    }
    
    func isServerRunning() -> Bool {
        // TODO: 调用Go函数
        return false
    }
    
    func getServerPort() -> Int {
        // TODO: 调用Go函数
        return 5701
    }
    
    func getServerURL() -> String {
        // TODO: 调用Go函数
        return "http://127.0.0.1:5701"
    }
    
    func getServerState() -> String {
        // TODO: 调用Go函数
        return "{\"port\":5701,\"running\":false}"
    }
    
    func initDataDir(_ dataDir: String) -> String {
        // TODO: 调用Go函数
        return "{\"success\":true}"
    }
    
    func initWebDir(_ webDir: String, assetsData: Data) -> String {
        // TODO: 调用Go函数
        return "{\"success\":true}"
    }
    
    func getVersion() -> String {
        return "2.2.14-mobile"
    }
    
    func logMessage(_ tag: String, message: String) {
        print("[\(tag)] \(message)")
    }
}
