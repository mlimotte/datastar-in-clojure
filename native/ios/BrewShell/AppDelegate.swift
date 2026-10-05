// BrewShell for iOS: a thin native shell over the Brew Board server.
// The app knows three things: where the server is, how to navigate (fetched from
// /native/path-config), and how to answer bridge messages. Every screen is a server route.

import UIKit
import UserNotifications

let serverURL = URL(string: "http://localhost:8080/")!

@main
class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?
    let navigator = Navigator()

    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        window = UIWindow(frame: UIScreen.main.bounds)
        window?.rootViewController = navigator.navigationController
        window?.makeKeyAndVisible()

        navigator.start(at: serverURL)

        // Local notifications ("Ada, your latte is ready") need permission; remote push needs a token.
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
        application.registerForRemoteNotifications()
        return true
    }

    // Native → web: hand the push token to the page, which posts it to /native/device.
    func application(_ application: UIApplication,
                     didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        let token = deviceToken.map { String(format: "%02x", $0) }.joined()
        navigator.sendToWeb(["type": "push-token", "token": token])
    }

    func application(_ application: UIApplication,
                     didFailToRegisterForRemoteNotificationsWithError error: Error) {
        // The simulator has no APNs. A toy token lets the rest of the bridge run.
        navigator.sendToWeb(["type": "push-token", "token": "simulator-\(UUID().uuidString)"])
    }
}
