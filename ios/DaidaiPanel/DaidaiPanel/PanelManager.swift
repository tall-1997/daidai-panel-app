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

    private(set) var isRunning = false
    private var dataDir: URL?
    private var webDir: URL?
    private let port = 5701

    // MARK: - Initialization

    private init() {
        setupDirectories()
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

        print("PanelManager initialized - dataDir: \(dataDir?.path ?? "nil"), webDir: \(webDir?.path ?? "nil")")
    }

    // MARK: - Service Control

    func startService(completion: @escaping (Bool) -> Void) {
        guard !isRunning else {
            completion(true)
            return
        }

        // TODO: Initialize gomobile binding and start Go server
        // For now, simulate starting
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            DispatchQueue.main.async {
                self?.isRunning = true
                self?.delegate?.panelManager(self!, didUpdateStatus: .started)
                completion(true)
            }
        }
    }

    func stopService() {
        guard isRunning else {
            return
        }

        // TODO: Stop gomobile binding
        isRunning = false
        delegate?.panelManager(self, didUpdateStatus: .stopped)
    }

    // MARK: - Status

    func getStatus() -> [String: Any] {
        return [
            "running": isRunning,
            "port": port,
            "dataDir": dataDir?.path ?? "",
            "webDir": webDir?.path ?? "",
            "gomobileInitialized": false
        ]
    }

    func getServerURL() -> String {
        return "http://127.0.0.1:\(port)"
    }

    // MARK: - Cleanup

    func cleanup() {
        isRunning = false
    }
}
