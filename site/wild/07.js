EE.addWild("07", [
 {
  "id": "07-hotwire-android-path-config",
  "engine": "Hotwire Native Android",
  "repo": "hotwired/hotwire-native-android",
  "sha": "df29a60cfc0e87fbe5bc0f5c2dc90130cfb7dba5",
  "path": "core/src/main/kotlin/dev/hotwire/core/turbo/config/PathConfiguration.kt",
  "lang": "kotlin",
  "title": "Hotwire Native Android <code>PathConfiguration</code>: cached copy first, then the network",
  "prompt": "Our shell fetches <code>/native/path-config</code> at launch. What does Hotwire use before the network answers, and what happens when two rules match the same path?",
  "notes": "<p>The path-configuration idea in part 7 comes from Hotwire Native. Its configuration is a JSON file of <code>rules</code>, each with regex <code>patterns</code> and a <code>properties</code> map, served by the app's own backend. <code>load</code> first applies, synchronously, a cached copy of the last remote file or else the bundled asset. Then it fetches the remote file in a coroutine and applies it when it arrives. The app always has rules, even offline on its first screen. Our reference shells have no cached copy: they fetch at launch, and every path falls back to <code>push</code> if that fails.</p><p>Matching cascades. <code>PathConfigurationData.properties</code> walks every rule and merges the properties of each match with <code>putAll</code>, so later rules override earlier ones key by key. The result is memoized per URL in <code>cachedProperties</code>, which is cleared when a new configuration loads. Ours is first match wins, with one flat <code>presentation</code> key. Cascading lets a broad rule set <code>context</code> for <code>/orders/.*</code> while a later, narrower rule overrides only <code>presentation</code> for one path. With first match wins, you would repeat every key.</p>",
  "segments": [
   {
    "start": 86,
    "end": 107,
    "code": "    fun load(\n        context: Context,\n        location: Location,\n        options: LoaderOptions\n    ) {\n        logDebug(\"pathConfigurationLoading\", location.toString())\n\n        val appContext = context.applicationContext\n        loadingJob?.cancel()\n\n        loader.loadCachedOrBundledConfiguration(appContext, location)?.let {\n            applyLoadedState(it)\n        }\n\n        loadingJob = loadingScope.launch {\n            location.remoteFileUrl?.let { url ->\n                loader.loadRemoteConfigurationForUrl(appContext, url, options)?.let {\n                    applyLoadedState(it)\n                }\n            }\n        }\n    }"
   },
   {
    "start": 118,
    "end": 127,
    "code": "    fun properties(location: String): PathConfigurationProperties {\n        synchronized(this) {\n            cachedProperties[location]?.let { return it }\n\n            val properties = currentConfiguration.properties(location)\n            cachedProperties[location] = properties\n\n            return properties\n        }\n    }"
   }
  ]
 },
 {
  "id": "07-hotwire-ios-bridge",
  "engine": "Hotwire Native iOS",
  "repo": "hotwired/hotwire-native-ios",
  "sha": "dbc4fc0fec0d2401f0be46015793bfcb29ec07a4",
  "path": "Source/Bridge/Bridge.swift",
  "lang": "swift",
  "title": "Hotwire Native iOS <code>Bridge</code>: a script injected into every page, and one message handler",
  "prompt": "Our page defines <code>window.BrewShell.post</code> in the server-rendered HTML. Where does Hotwire's web-side bridge come from, and what is the first message the native side waits for?",
  "notes": "<p>Hotwire uses the same two mechanisms as our iOS shell. Web to native: <code>userContentController.add(…, name: \"bridge\")</code> exposes <code>window.webkit.messageHandlers.bridge.postMessage</code> to the page, and each message arrives in <code>scriptMessageHandlerDidReceiveMessage</code>. Native to web: <code>evaluateJavaScript</code> runs a call to <code>window.nativeBridge</code>'s <code>register</code>, <code>unregister</code> or <code>replyWith</code>. Ours uses <code>messageHandlers.brew</code> in one direction. In the other it dispatches a <code>brew:native</code> <code>CustomEvent</code> on <code>window</code>, which <code>data-on:brew:native__window</code> picks up so that Datastar handles it like any other event.</p><p>The difference is who ships the web half. Hotwire injects <code>bridge.js</code> from the app bundle as a <code>WKUserScript</code> at document start, so the protocol's web side is versioned with the app binary. The native side then waits for a <code>\"ready\"</code> message from the page (line 177) before it treats the bridge as initialized. Our shim is part of the server-rendered page (lesson 07's <code>page</code>), so it changes with a deploy. The native half that receives the messages still needs a release whenever it must handle a new kind of message. Hotwire's named bridge components make that contract explicit on both sides.</p>",
  "segments": [
   {
    "start": 111,
    "end": 136,
    "code": "    /// This needs to match whatever the JavaScript file uses\n    private let bridgeGlobal = \"window.nativeBridge\"\n\n    /// The webkit.messageHandlers name\n    private let scriptHandlerName = \"bridge\"\n\n    @MainActor\n    private func callBridgeFunction(_ function: JavaScriptBridgeFunction, arguments: [Any]) async throws {\n        let js = JavaScript(object: bridgeGlobal, functionName: function.rawValue, arguments: arguments)\n        try await evaluate(javaScript: js)\n    }\n\n    // MARK: - Configuration\n\n    /// Configure the bridge in the provided web view\n    private func loadIntoWebView() {\n        guard let configuration = webView?.configuration else { return }\n\n        // Install user script and message handlers in web view\n        if let userScript = makeUserScript() {\n            configuration.userContentController.addUserScript(userScript)\n        }\n\n        let scriptMessageHandler = ScriptMessageHandler(delegate: self)\n        configuration.userContentController.add(scriptMessageHandler, name: scriptHandlerName)\n    }"
   },
   {
    "start": 174,
    "end": 188,
    "code": "extension Bridge: ScriptMessageHandlerDelegate {\n    @MainActor\n    func scriptMessageHandlerDidReceiveMessage(_ scriptMessage: WKScriptMessage) {\n        if let event = scriptMessage.body as? String, event == \"ready\" {\n            delegate?.bridgeDidInitialize()\n            return\n        }\n\n        if let message = InternalMessage(scriptMessage: scriptMessage) {\n            delegate?.bridgeDidReceiveMessage(message.toMessage())\n            return\n        }\n\n        logger.warning(\"Unhandled message received: \\(String(describing: scriptMessage.body))\")\n    }"
   }
  ]
 },
 {
  "id": "07-hyperview-behaviors",
  "engine": "Hyperview",
  "repo": "Instawork/hyperview",
  "sha": "444c9b24e65bb7370eab3a0edf3c1b4e11d4ac86",
  "path": "src/services/behaviors/index.ts",
  "lang": "typescript",
  "title": "Hyperview: native screens from XML, and links that say how to navigate",
  "prompt": "Our shell asks <code>/native/path-config</code> how to present a URL. Where does a Hyperview app get that answer, and what is its default?",
  "notes": "<p>Hyperview takes the other road from a WebView shell. The server returns HXML, an XML format for native screens, and a React Native client renders it with native components. There is no HTML and no browser engine. Interactions are <code>&lt;behavior&gt;</code> elements. <code>createActionHandler</code> reads the behavior's <code>action</code> attribute, and a missing one means <code>push</code>, the same fallback our reference shells use for a path no rule matches. The difference is where the decision lives. In Hyperview each link carries its own <code>action</code> (<code>push</code>; <code>new</code>, which opens a modal; <code>back</code>; <code>navigate</code>; …). Our shell and Hotwire Native keep URL-pattern rules in one served document.</p><p><code>performUpdate</code> is Hyperview's version of Datastar's patch modes: a fetched element replaces the target (<code>replace</code>), replaces its children (<code>replace-inner</code>), or is appended or prepended. These edit the HXML document and return a new <code>Document</code> (<code>shallowCloneToRoot</code>) for the client to render. Datastar's corresponding modes are <code>outer</code>, <code>inner</code>, <code>append</code> and <code>prepend</code>. Its default, <code>outer</code>, morphs where Hyperview's <code>replace</code> swaps.</p>",
  "segments": [
   {
    "start": 71,
    "end": 99,
    "code": "export const performUpdate = (\n  action: UpdateAction,\n  targetElement: Element,\n  newElement: Element,\n): Document => {\n  if (action === ACTIONS.REPLACE) {\n    const { parentNode } = targetElement;\n    if (parentNode) {\n      parentNode.replaceChild(newElement, targetElement);\n      return shallowCloneToRoot(parentNode as Element);\n    }\n  }\n\n  if (action === ACTIONS.REPLACE_INNER) {\n    let child: Node | null | undefined = targetElement.firstChild;\n    // Remove the target's children\n    while (child !== null && child !== undefined) {\n      const nextChild: Node | null | undefined = child.nextSibling;\n      targetElement.removeChild(child);\n      child = nextChild;\n    }\n    targetElement.appendChild(newElement);\n    return shallowCloneToRoot(targetElement);\n  }\n\n  if (action === ACTIONS.APPEND) {\n    targetElement.appendChild(newElement);\n    return shallowCloneToRoot(targetElement);\n  }"
   },
   {
    "start": 173,
    "end": 183,
    "code": "export const createActionHandler = (\n  behaviorElement: Element,\n  onUpdate: HvComponentOnUpdate,\n) => {\n  const action =\n    behaviorElement.getAttribute(BEHAVIOR_ATTRIBUTES.ACTION) ||\n    NAV_ACTIONS.PUSH;\n  if (Object.values(NAV_ACTIONS).indexOf(action as NavAction) >= 0) {\n    return (element: Element) => {\n      const href = behaviorElement.getAttribute(BEHAVIOR_ATTRIBUTES.HREF);\n      const targetId = behaviorElement.getAttribute(BEHAVIOR_ATTRIBUTES.TARGET);"
   }
  ]
 }
]);
