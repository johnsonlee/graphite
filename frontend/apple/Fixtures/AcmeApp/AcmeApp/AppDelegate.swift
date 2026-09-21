import UIKit

/// A UIKit delegate: its methods are exposed to Objective-C and the index store keys them
/// by Clang USRs, which the demangler cannot read.
final class AppDelegate: NSObject, UIApplicationDelegate {
    var window: UIWindow?

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        application.registerForRemoteNotifications()
        // Swift calling Objective-C through the bridging header.
        LegacyStore.shared().storeValue("on", forKey: "legacy.mode")
        return FeatureFlags.isEnabled("launch.tracking", default: false)
    }

    @objc func handleBackground(_ notification: Notification) {
        _ = CheckoutService(client: PaymentClient(endpoint: "https://api.acme.example")).pay(amount: 1.0)
    }
}
