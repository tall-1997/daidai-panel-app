import UIKit

class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    
    var window: UIWindow?
    
    func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options connectionOptions: UIScene.ConnectionOptions) {
        guard let windowScene = (scene as? UIWindowScene) else { return }
        
        window = UIWindow(windowScene: windowScene)
        
        let viewController = ViewController()
        let navigationController = UINavigationController(rootViewController: viewController)
        
        window?.rootViewController = navigationController
        window?.makeKeyAndVisible()
    }
    
    func sceneDidDisconnect(_ scene: UIScene) {
        // 场景断开时，确保服务继续运行
    }
    
    func sceneDidBecomeActive(_ scene: UIScene) {
        // 场景激活时，刷新WebView
        NotificationCenter.default.post(name: .refreshWebView, object: nil)
    }
    
    func sceneDidEnterBackground(_ scene: UIScene) {
        // 进入后台时，确保服务继续运行
        PanelManager.shared.ensureBackgroundRunning()
    }
}
