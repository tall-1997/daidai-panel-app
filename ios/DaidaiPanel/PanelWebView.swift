import WebKit

/// 自定义WebView，支持本地HTTP访问
class PanelWebView: WKWebView {
    
    // MARK: - Initialization
    
    override init(frame: CGRect, configuration: WKWebViewConfiguration) {
        super.init(frame: frame, configuration: configuration)
        setupWebView()
    }
    
    required init?(coder: NSCoder) {
        super.init(coder: coder)
        setupWebView()
    }
    
    // MARK: - Setup
    
    private func setupWebView() {
        // 启用JavaScript
        configuration.preferences.javaScriptEnabled = true
        
        // 启用文件访问
        configuration.preferences.setValue(true, forKey: "allowFileAccessFromFileURLs")
        configuration.setValue(true, forKey: "allowUniversalAccessFromFileURLs")
        
        // 启用手势导航
        allowsBackForwardNavigationGestures = true
        
        // 设置自定义User-Agent
        evaluateJavaScript("navigator.userAgent") { [weak self] result, error in
            if let userAgent = result as? String {
                self?.customUserAgent = userAgent + " DaidaiPanel/1.0"
            }
        }
    }
    
    // MARK: - Public Methods
    
    /// 加载面板URL
    func loadPanelURL(_ urlString: String) {
        guard let url = URL(string: urlString) else { return }
        
        let request = URLRequest(
            url: url,
            cachePolicy: .reloadIgnoringLocalCacheData,
            timeoutInterval: 30
        )
        
        load(request)
    }
    
    /// 注入JavaScript
    func injectJavaScript(_ script: String, completion: ((Any?, Error?) -> Void)? = nil) {
        evaluateJavaScript(script, completionHandler: completion)
    }
    
    /// 获取当前URL
    func getCurrentURL() -> String? {
        return url?.absoluteString
    }
    
    /// 获取标题
    func getTitle() -> String? {
        return title
    }
    
    // MARK: - JavaScript Bridge
    
    /// 注册JavaScript接口
    func registerJavaScriptBridge() {
        let script = """
        window.DaidaiBridge = {
            getVersion: function() {
                return '\(PanelManager.shared.getVersion())';
            },
            getPlatform: function() {
                return 'ios';
            },
            log: function(message) {
                console.log('[DaidaiBridge]', message);
            }
        };
        """
        
        let userScript = WKUserScript(
            source: script,
            injectionTime: .atDocumentEnd,
            forMainFrameOnly: true
        )
        
        configuration.userContentController.addUserScript(userScript)
    }
}

// MARK: - WKScriptMessageHandler

extension PanelWebView: WKScriptMessageHandler {
    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        // 处理来自JavaScript的消息
        print("[PanelWebView] Received message: \(message.name)")
        
        if let body = message.body as? [String: Any] {
            let action = body["action"] as? String ?? ""
            let data = body["data"]
            
            switch action {
            case "getVersion":
                let version = PanelManager.shared.getVersion()
                evaluateJavaScript("window.onVersionReceived && window.onVersionReceived('\(version)')")
                
            case "getServerState":
                let state = PanelManager.shared.getServerURL()
                evaluateJavaScript("window.onServerStateReceived && window.onServerStateReceived('\(state)')")
                
            default:
                print("[PanelWebView] Unknown action: \(action)")
            }
        }
    }
}
