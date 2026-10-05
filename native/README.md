# Native shells for Brew Board

Reference code for part 7, "Replacing the React Native app". Each shell is a thin native app:
every screen is a server route rendered by Brew Board and driven by Datastar inside a web view.
The shell itself knows only three things:

1. **Where the server is**: `serverURL` (iOS) / `SERVER` (Android).
2. **How to navigate**: it fetches `GET /native/path-config` at launch. A link whose path
   matches a `push` rule opens a new native screen, `modal` presents one, and `root` resets the
   stack. Unmatched paths push. The rules live on the server, so changing navigation is a
   deploy, not an app store release.
3. **The bridge**:
   - **Web → native.** The server sends a bridge script (`brewboard.native/bridge-script`).
     It calls `window.BrewShell.post(msg)`, and the page forwards that to
     `window.webkit.messageHandlers.brew` (iOS) or `window.BrewAndroid.postMessage` (Android).
     The shells handle `{"type":"haptic"}` and
     `{"type":"notify","title":…,"body":…}`. In a plain browser the call only logs.
   - **Native → web.** The shell dispatches
     `window.dispatchEvent(new CustomEvent('brew:native', {detail: {...}}))`. The page listens
     with `data-on:brew:native__window`. For `{"type":"push-token"}` it sets `$pushToken` and
     calls `@post('/native/device')`.

Both shells append `BrewShell/1` to the User-Agent. The server checks for it
(`brewboard.native/shell-request?`) and leaves out the web header, because the shell draws
native chrome.

| File | What |
|---|---|
| `ios/BrewShell/AppDelegate.swift` | window, launch, notification permission, push token → web |
| `ios/BrewShell/Navigator.swift` | path-config fetch and rules, root/push/modal, native → web messages |
| `ios/BrewShell/WebScreen.swift` | one WKWebView per screen, link interception, `brew` message handler |
| `android/app/src/main/java/dev/brewboard/shell/ShellActivity.kt` | the whole Android shell: one activity per screen |
| `android/app/src/main/AndroidManifest.xml`, `android/app/build.gradle.kts` | minimal module wiring |

## Run (by hand; CI does not build these)

Start the server with `cd clojure && clojure -M -m brewboard.main`.

- **iOS:** create an Xcode "App" project (UIKit, no storyboard), delete its generated
  app/scene delegates and the `UIApplicationSceneManifest` entry in Info.plist, and add these
  three files. For plain `http://localhost` add `NSAppTransportSecurity →
  NSAllowsLocalNetworking = YES`. The simulator has no APNs, so the shell sends a
  `simulator-…` token.
- **Android:** add the `app` module to an Android Studio project. The emulator reaches the host
  at `10.0.2.2:8080`. A placeholder `emulator-…` token stands in for an FCM token.

## What was checked, and what was not (2026-10-05)

- **Swift: typechecked, not built or run.** No iOS SDK is installed here (Command Line Tools
  only), so the check used the Mac Catalyst SDK, which provides UIKit and WebKit:
  `swiftc -typecheck -swift-version {5,6} -sdk $(xcrun --show-sdk-path)
  -target arm64-apple-ios17.0-macabi -Fsystem $SDK/System/iOSSupport/System/Library/Frameworks
  *.swift`. It gives 0 errors and 0 warnings in both Swift 5 and Swift 6 language modes.
  Nothing has run on a device or simulator.
- **Kotlin: not checked.** `kotlinc` and the Android SDK are not installed here.
- **The server side is tested:** `clojure/test/brewboard/native_test.clj` covers User-Agent
  detection, path-config rules, the bridge script, and ready notifications.
  `brewboard.demo` prints the bridge scripts sent when an order turns ready.
