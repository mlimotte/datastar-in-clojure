// Navigation rules come from the server (GET /native/path-config), so a new screen or a
// change from "push" to "modal" ships with a server deploy, not an App Store release.

import UIKit
import WebKit

struct PathConfig: Decodable {
    struct Rule: Decodable {
        let patterns: [String]
        let presentation: String   // "root", "push" or "modal"
    }
    let rules: [Rule]

    func presentation(for path: String) -> String {
        for rule in rules {
            for pattern in rule.patterns where path.range(of: pattern, options: .regularExpression) != nil {
                return rule.presentation
            }
        }
        return "push"
    }
}

@MainActor
final class Navigator {
    let navigationController = UINavigationController()
    private var config = PathConfig(rules: [])
    private var pendingMessages: [[String: String]] = []

    func start(at url: URL) {
        let configURL = url.appendingPathComponent("native/path-config")
        URLSession.shared.dataTask(with: configURL) { data, _, _ in
            let loaded = data.flatMap { try? JSONDecoder().decode(PathConfig.self, from: $0) }
            DispatchQueue.main.async {
                if let loaded { self.config = loaded }
                self.visit(url)
            }
        }.resume()
    }

    func visit(_ url: URL) {
        let screen = WebScreen(url: url, navigator: self)
        switch config.presentation(for: url.path.isEmpty ? "/" : url.path) {
        case "root":
            navigationController.setViewControllers([screen], animated: false)
        case "modal":
            let modal = UINavigationController(rootViewController: screen)
            screen.navigationItem.leftBarButtonItem = UIBarButtonItem(
                systemItem: .close, primaryAction: UIAction { _ in modal.dismiss(animated: true) })
            navigationController.present(modal, animated: true)
        default:
            navigationController.pushViewController(screen, animated: true)
        }
    }

    // Native → web. The page listens with data-on:brew:native__window.
    func sendToWeb(_ message: [String: String]) {
        guard let root = navigationController.viewControllers.first as? WebScreen, root.isLoaded else {
            pendingMessages.append(message)
            return
        }
        root.dispatch(message)
    }

    func screenDidLoad(_ screen: WebScreen) {
        guard screen === navigationController.viewControllers.first else { return }
        pendingMessages.forEach(screen.dispatch)
        pendingMessages.removeAll()
    }
}
