import Foundation
import UIKit

// Import gomobile generated framework
// This will be available after gomobile bind generates the xcframework
// import DaidaiPanel

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
    
    // gomobile binding instance
    // private var daidaiPanel: DaidaiPanel?
    
    private var process: Process?
    private(set) var isRunning = false
    private var dataDir: URL?
    private var webDir: URL?
    private var binaryPath: URL?
    private let port = 5701
    
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
        
        // Web assets directory - try bundle first, then documents
        if let bundleWeb = Bundle.main.url(forResource: "web", withExtension: nil) {
            webDir = bundleWeb
        } else {
            webDir = documentsDir.appendingPathComponent("web")
            copyWebAssetsIfNeeded()
        }
        
        // Binary path
        binaryPath = documentsDir.appendingPathComponent("daidai-panel")
        
        // Copy resources if needed
        copyResourcesIfNeeded()
        
        // Initialize gomobile binding
        // initGomobileBinding()
    }
    
    private func copyWebAssetsIfNeeded() {
        let fileManager = FileManager.default
        
        // Copy web assets from bundle if available
        if let bundleWeb = Bundle.main.url(forResource: "web", withExtension: nil) {
            if !fileManager.fileExists(atPath: webDir!.path) {
                try? fileManager.copyItem(at: bundleWeb, to: webDir!)
            }
        }
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
        
        // Create config file if needed
        let configFile = dataDir!.appendingPathComponent("config.yaml")
        if !fileManager.fileExists(atPath: configFile.path) {
            let configContent = """
                server:
                  port: \(port)
                  mode: release
                  web_dir: \(webDir?.path ?? "")
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
    
    /*
    private func initGomobileBinding() {
        // Initialize gomobile binding
        daidaiPanel = DaidaiPanel()
        
        guard let dataDir = dataDir, let webDir = webDir else {
            print("Error: dataDir or webDir not set")
            return
        }
        
        do {
            try daidaiPanel?.initialize(dataDir.path, webDir: webDir.path)
            print("Gomobile binding initialized successfully")
        } catch {
            print("Failed to initialize gomobile binding: \(error)")
        }
    }
    */
    
    // MARK: - Service Control
    
    func startService(completion: @escaping (Bool) -> Void) {
        guard !isRunning else {
            completion(true)
            return
        }
        
        // Use gomobile binding if available
        /*
        if let daidaiPanel = daidaiPanel {
            do {
                try daidaiPanel.start()
                isRunning = true
                delegate?.panelManager(self, didUpdateStatus: .started)
                completion(true)
            } catch {
                print("Failed to start via gomobile: \(error)")
                completion(false)
            }
            return
        }
        */
        
        // Fallback to process execution
        startProcess(completion: completion)
    }
    
    private func startProcess(completion: @escaping (Bool) -> Void) {
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
        // Use gomobile binding if available
        /*
        if let daidaiPanel = daidaiPanel {
            do {
                try daidaiPanel.stop()
                isRunning = false
                delegate?.panelManager(self, didUpdateStatus: .stopped)
            } catch {
                print("Failed to stop via gomobile: \(error)")
            }
            return
        }
        */
        
        // Fallback to process termination
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
            "port": port,
            "dataDir": dataDir?.path ?? "",
            "webDir": webDir?.path ?? ""
        ]
    }
    
    func getServerURL() -> String {
        return "http://127.0.0.1:\(port)"
    }
    
    // MARK: - Cleanup
    
    func cleanup() {
        /*
        daidaiPanel?.cleanup()
        daidaiPanel = nil
        */
        stopService()
    }
}
