import UIKit

@main
class AppDelegate: UIResponder, UIApplicationDelegate {
    
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        // 启动面板服务
        PanelManager.shared.startServerIfNeeded()
        return true
    }
    
    func applicationDidEnterBackground(_ application: UIApplication) {
        // 进入后台时，确保服务继续运行
        PanelManager.shared.ensureBackgroundRunning()
    }
    
    func applicationWillEnterForeground(_ application: UIApplication) {
        // 回到前台时，刷新WebView
        NotificationCenter.default.post(name: .refreshWebView, object: nil)
    }
}
