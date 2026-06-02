import Foundation
import UIKit

// MARK: - PanelStatus

enum PanelStatus {
    case started
    case stopped
    case error(String)
}

// MARK: - PanelManagerDelegate

protocol PanelManagerDelegate: AnyObject {
    func panelManager(_ manager: PanelManager, didUpdateStatus status: PanelStatus)
}

// MARK: - PanelManager

class PanelManager {
    
    // MARK: - Properties
    
    static let shared = PanelManager()
    
    weak var delegate: PanelManagerDelegate?
    
    private var process: Process?
    private(set) var isRunning = false
    private var dataDir: URL?
    private var webDir: URL?
    private var binaryPath: URL?
    
    // MARK: - Initialization
    
    private init() {
        setupDirectories()
    }
    
    // MARK: - Setup
    
    private func setupDirectories() {
        let fileManager = FileManager.default
        
        // App documents directory
        guard let documentsDir = fileManager.urls(for: .documentDirectory, in: .userDomainMask).first else {
            return
        }
        
        // Panel data directory
        dataDir = documentsDir.appendingPathComponent("Dumb-Panel")
        try? fileManager.createDirectory(at: dataDir!, withIntermediateDirectories: true)
        
        // Database directory
        let dbDir = dataDir!.appendingPathComponent("db")
        try? fileManager.createDirectory(at: dbDir, withIntermediateDirectories: true)
        
        // Scripts directory
        let scriptsDir = dataDir!.appendingPathComponent("scripts")
        try? fileManager.createDirectory(at: scriptsDir, withIntermediateDirectories: true)
        
        // Log directory
        let logDir = dataDir!.appendingPathComponent("log")
        try? fileManager.createDirectory(at: logDir, withIntermediateDirectories: true)
        
        // Web assets directory
        webDir = documentsDir.appendingPathComponent("web")
        
        // Binary path
        binaryPath = documentsDir.appendingPathComponent("daidai-panel")
        
        // Copy resources if needed
        copyResourcesIfNeeded()
    }
    
    private func copyResourcesIfNeeded() {
        let fileManager = FileManager.default
        
        // Copy binary from bundle
        if let bundleBinary = Bundle.main.url(forResource: "daidai-panel", withExtension: nil) {
            if !fileManager.fileExists(atPath: binaryPath!.path) {
                try? fileManager.copyItem(at: bundleBinary, to: binaryPath!)
                try? fileManager.setAttributes([.posixPermissions: 0o755], ofItemAtPath: binaryPath!.path)
            }
        }
        
        // Copy web assets from bundle
        if let bundleWeb = Bundle.main.url(forResource: "web", withExtension: nil) {
            if !fileManager.fileExists(atPath: webDir!.path) {
                try? fileManager.copyItem(at: bundleWeb, to: webDir!)
            }
        }
        
        // Create config file if needed
        let configFile = dataDir!.appendingPathComponent("config.yaml")
        if !fileManager.fileExists(atPath: configFile.path) {
            let configContent = """
                server:
                  port: 5701
                  mode: release
                  web_dir: \(webDir!.path)
                database:
                  path: \(dataDir!.appendingPathComponent("db/panel.db").path)
                data:
                  dir: \(dataDir!.path)
                  scripts_dir: \(dataDir!.appendingPathComponent("scripts").path)
                  log_dir: \(dataDir!.appendingPathComponent("log").path)
                cors:
                  origins:
                    - "*"
                """
            try? configContent.write(to: configFile, atomically: true, encoding: .utf8)
        }
    }
    
    // MARK: - Service Control
    
    func startService(completion: @escaping (Bool) -> Void) {
        guard !isRunning else {
            completion(true)
            return
        }
        
        guard let binaryPath = binaryPath, FileManager.default.fileExists(atPath: binaryPath.path) else {
            completion(false)
            return
        }
        
        let configFile = dataDir!.appendingPathComponent("config.yaml")
        
        let process = Process()
        process.executableURL = binaryPath
        process.arguments = ["-config", configFile.path]
        
        // Set environment variables
        var environment = ProcessInfo.processInfo.environment
        environment["DAIDAI_CONFIG"] = configFile.path
        environment["DAIDAI_DATA_DIR"] = dataDir?.path
        environment["DAIDAI_WEB_DIR"] = webDir?.path
        process.environment = environment
        
        // Capture output
        let outputPipe = Pipe()
        process.standardOutput = outputPipe
        process.standardError = outputPipe
        
        outputPipe.fileHandleForReading.readabilityHandler = { [weak self] handle in
            let data = handle.availableData
            if let output = String(data: data, encoding: .utf8), !output.isEmpty {
                print("Panel: \(output)")
            }
        }
        
        process.terminationHandler = { [weak self] process in
            DispatchQueue.main.async {
                self?.isRunning = false
                self?.delegate?.panelManager(self!, didUpdateStatus: .stopped)
                
                // Restart if crashed
                if process.terminationReason == .uncaughtSignal {
                    DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
                        self?.startService { _ in }
                    }
                }
            }
        }
        
        do {
            try process.run()
            self.process = process
            self.isRunning = true
            self.delegate?.panelManager(self, didUpdateStatus: .started)
            completion(true)
        } catch {
            print("Failed to start panel: \(error)")
            completion(false)
        }
    }
    
    func stopService() {
        guard let process = process, process.isRunning else {
            return
        }
        
        process.interrupt()
        
        // Wait for process to terminate
        DispatchQueue.main.asyncAfter(deadline: .now() + 1) { [weak self] in
            if process.isRunning {
                process.terminate()
            }
            self?.process = nil
            self?.isRunning = false
        }
    }
    
    // MARK: - Status
    
    func getStatus() -> [String: Any] {
        return [
            "running": isRunning,
            "port": 5701,
            "dataDir": dataDir?.path ?? "",
            "webDir": webDir?.path ?? ""
        ]
    }
}
