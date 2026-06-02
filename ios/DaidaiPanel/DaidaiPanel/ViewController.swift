import UIKit
import WebKit

class ViewController: UIViewController {
    
    // MARK: - Properties
    
    private var webView: WKWebView!
    private var panelManager: PanelManager!
    private let panelURL = URL(string: "http://127.0.0.1:5701")!
    
    // MARK: - Lifecycle
    
    override func viewDidLoad() {
        super.viewDidLoad()
        
        setupWebView()
        setupPanelManager()
        startPanel()
    }
    
    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        navigationController?.setNavigationBarHidden(true, animated: animated)
    }
    
    // MARK: - Setup
    
    private func setupWebView() {
        let configuration = WKWebViewConfiguration()
        configuration.allowsInlineMediaPlayback = true
        
        webView = WKWebView(frame: view.bounds, configuration: configuration)
        webView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        webView.navigationDelegate = self
        webView.uiDelegate = self
        webView.allowsBackForwardNavigationGestures = true
        
        view.addSubview(webView)
    }
    
    private func setupPanelManager() {
        panelManager = PanelManager.shared
        panelManager.delegate = self
    }
    
    // MARK: - Panel Control
    
    private func startPanel() {
        panelManager.startService { [weak self] success in
            DispatchQueue.main.async {
                if success {
                    self?.loadPanel()
                } else {
                    self?.showErrorAlert(message: "启动面板失败")
                }
            }
        }
    }
    
    private func loadPanel() {
        // Wait for server to be ready
        var attempts = 0
        let maxAttempts = 50
        
        Timer.scheduledTimer(withTimeInterval: 0.2, repeats: true) { [weak self] timer in
            guard let self = self else {
                timer.invalidate()
                return
            }
            
            attempts += 1
            
            if self.panelManager.isRunning || attempts >= maxAttempts {
                timer.invalidate()
                
                if self.panelManager.isRunning {
                    let request = URLRequest(url: self.panelURL)
                    self.webView.load(request)
                } else {
                    self.showErrorAlert(message: "面板启动超时")
                }
            }
        }
    }
    
    private func showErrorAlert(message: String) {
        let alert = UIAlertController(title: "错误", message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "重试", style: .default) { [weak self] _ in
            self?.startPanel()
        })
        alert.addAction(UIAlertAction(title: "退出", style: .destructive) { _ in
            exit(0)
        })
        present(alert, animated: true)
    }
}

// MARK: - WKNavigationDelegate

extension ViewController: WKNavigationDelegate {
    
    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard let url = navigationAction.request.url else {
            decisionHandler(.allow)
            return
        }
        
        // Allow local panel URLs
        if url.host == "127.0.0.1" || url.host == "localhost" {
            decisionHandler(.allow)
            return
        }
        
        // Open external URLs in Safari
        if url.scheme == "http" || url.scheme == "https" {
            UIApplication.shared.open(url)
            decisionHandler(.cancel)
            return
        }
        
        decisionHandler(.allow)
    }
    
    func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
        // Show loading indicator if needed
    }
    
    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        // Hide loading indicator if needed
    }
    
    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        print("WebView failed: \(error.localizedDescription)")
        // Try to reload after a delay
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) { [weak self] in
            self?.webView.reload()
        }
    }
    
    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        print("WebView provisional navigation failed: \(error.localizedDescription)")
        // Try to reload after a delay
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) { [weak self] in
            self?.webView.reload()
        }
    }
}

// MARK: - WKUIDelegate

extension ViewController: WKUIDelegate {
    
    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration, for navigationAction: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        // Open new windows in Safari
        if let url = navigationAction.request.url {
            UIApplication.shared.open(url)
        }
        return nil
    }
}

// MARK: - PanelManagerDelegate

extension ViewController: PanelManagerDelegate {
    
    func panelManager(_ manager: PanelManager, didUpdateStatus status: PanelStatus) {
        DispatchQueue.main.async { [weak self] in
            switch status {
            case .started:
                self?.loadPanel()
            case .stopped:
                self?.showErrorAlert(message: "面板已停止")
            case .error(let message):
                self?.showErrorAlert(message: message)
            }
        }
    }
}
