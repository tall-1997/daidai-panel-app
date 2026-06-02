import Foundation
import UIKit
import Mobile

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

    // gomobile binding instance (from xcframework, module: Mobile)
    private var panel: DaidaiPanel?

    private(set) var isRunning = false
    private var dataDir: URL?
    private var webDir: URL?
    private let port = 5701

    // MARK: - Initialization

    private init() {
        setupDirectories()
        initGomobileBinding()
    }

    // MARK: - Setup

    private func setupDirectories() {
        let fileManager = FileManager.default

        guard let documentsDir = fileManager.urls(for: .documentDirectory, in: .userDomainMask).first else {
            return
        }

        dataDir = documentsDir.appendingPathComponent("Dumb-Panel")
        try? fileManager.createDirectory(at: dataDir!, withIntermediateDirectories: true)

        let dbDir = dataDir!.appendingPathComponent("db")
        try? fileManager.createDirectory(at: dbDir, withIntermediateDirectories: true)

        let scriptsDir = dataDir!.appendingPathComponent("scripts")
        try? fileManager.createDirectory(at: scriptsDir, withIntermediateDirectories: true)

        let logDir = dataDir!.appendingPathComponent("log")
        try? fileManager.createDirectory(at: logDir, withIntermediateDirectories: true)

        // Web assets from bundle
        if let bundleWeb = Bundle.main.url(forResource: "web", withExtension: nil) {
            webDir = bundleWeb
        } else {
            webDir = documentsDir.appendingPathComponent("web")
            try? fileManager.createDirectory(at: webDir!, withIntermediateDirectories: true)
        }
    }

    private func initGomobileBinding() {
        guard let dataDir = dataDir, let webDir = webDir else {
            print("Error: dataDir or webDir not set")
            return
        }

        panel = NewDaidaiPanel()
        do {
            try panel?.initialize(dataDir.path, webDir: webDir.path)
            print("Gomobile binding initialized successfully")
        } catch {
            print("Failed to initialize gomobile binding: \(error)")
        }
    }

    // MARK: - Service Control

    func startService(completion: @escaping (Bool) -> Void) {
        guard !isRunning else {
            completion(true)
            return
        }

        guard let panel = panel else {
            print("Gomobile binding not initialized")
            completion(false)
            return
        }

        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            do {
                try panel.start()
                DispatchQueue.main.async {
                    self?.isRunning = true
                    self?.delegate?.panelManager(self!, didUpdateStatus: .started)
                    completion(true)
                }
            } catch {
                print("Failed to start panel: \(error)")
                DispatchQueue.main.async {
                    self?.delegate?.panelManager(self!, didUpdateStatus: .error(error.localizedDescription))
                    completion(false)
                }
            }
        }
    }

    func stopService() {
        guard let panel = panel, isRunning else {
            return
        }

        do {
            try panel.stop()
            isRunning = false
            delegate?.panelManager(self, didUpdateStatus: .stopped)
        } catch {
            print("Failed to stop panel: \(error)")
        }
    }

    // MARK: - Status

    func getStatus() -> [String: Any] {
        var status: [String: Any] = [
            "running": isRunning,
            "port": port,
            "dataDir": dataDir?.path ?? "",
            "webDir": webDir?.path ?? ""
        ]

        if let panel = panel {
            status["gomobileInitialized"] = true
            status["url"] = panel.getURL()
        } else {
            status["gomobileInitialized"] = false
        }

        return status
    }

    func getServerURL() -> String {
        if let panel = panel, isRunning {
            return panel.getURL()
        }
        return "http://127.0.0.1:\(port)"
    }

    // MARK: - Cleanup

    func cleanup() {
        panel?.cleanup()
        panel = nil
    }
}
