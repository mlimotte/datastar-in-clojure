// One screen = one WKWebView showing one server route. Datastar runs inside it as in
// any browser; the shell adds a User-Agent token and a message handler named "brew".

import UIKit
import UserNotifications
import WebKit

final class WebScreen: UIViewController, WKNavigationDelegate {
    private let url: URL
    private unowned let navigator: Navigator
    private var webView: WKWebView!
    private(set) var isLoaded = false

    init(url: URL, navigator: Navigator) {
        self.url = url
        self.navigator = navigator
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    override func loadView() {
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .default()   // shared by every screen, so they share the `sid` cookie
        config.applicationNameForUserAgent = "BrewShell/1"   // the server checks for this
        // Web → native: window.webkit.messageHandlers.brew.postMessage(msg)
        config.userContentController.add(BridgeHandler(), name: "brew")
        webView = WKWebView(frame: .zero, configuration: config)
        webView.navigationDelegate = self
        view = webView
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        webView.load(URLRequest(url: url))
    }

    // Links to other routes become native navigation; the current page never navigates away.
    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction,
                 decisionHandler: @escaping @MainActor @Sendable (WKNavigationActionPolicy) -> Void) {
        if action.navigationType == .linkActivated, let target = action.request.url,
           target.host == url.host {
            decisionHandler(.cancel)
            navigator.visit(target)
            return
        }
        decisionHandler(.allow)
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        title = webView.title
        isLoaded = true
        navigator.screenDidLoad(self)
    }

    func dispatch(_ message: [String: String]) {
        guard let data = try? JSONSerialization.data(withJSONObject: message),
              let json = String(data: data, encoding: .utf8) else { return }
        webView.evaluateJavaScript(
            "window.dispatchEvent(new CustomEvent('brew:native', {detail: \(json)}))")
    }
}

/// Answers messages the server sends through bridge scripts (see brewboard.native/bridge-script).
final class BridgeHandler: NSObject, WKScriptMessageHandler {
    func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
        guard let body = message.body as? [String: Any], let type = body["type"] as? String else { return }
        switch type {
        case "haptic":
            UINotificationFeedbackGenerator().notificationOccurred(.success)
        case "notify":
            let content = UNMutableNotificationContent()
            content.title = body["title"] as? String ?? "Brew Board"
            content.body = body["body"] as? String ?? ""
            UNUserNotificationCenter.current().add(
                UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil))
        default:
            print("BrewShell: unknown bridge message", type)
        }
    }
}
