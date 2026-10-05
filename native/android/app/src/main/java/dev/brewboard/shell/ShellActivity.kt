// BrewShell for Android: one WebView per screen, over the same Brew Board routes.
// Navigation rules come from GET /native/path-config; the bridge is a
// @JavascriptInterface named BrewAndroid (web → native) and a CustomEvent (native → web).

package dev.brewboard.shell

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.net.URL
import java.util.UUID
import kotlin.concurrent.thread

const val SERVER = "http://10.0.2.2:8080/"   // the host machine, seen from the emulator

object PathConfig {
    @Volatile private var rules: List<Pair<List<Regex>, String>> = emptyList()

    fun load() {
        val json = JSONObject(URL(SERVER + "native/path-config").readText())
        val list = json.getJSONArray("rules")
        rules = (0 until list.length()).map { i ->
            val rule = list.getJSONObject(i)
            val patterns = rule.getJSONArray("patterns")
            (0 until patterns.length()).map { Regex(patterns.getString(it)) } to rule.getString("presentation")
        }
    }

    fun presentation(path: String): String =
        rules.firstOrNull { (patterns, _) -> patterns.any { it.containsMatchIn(path) } }?.second ?: "push"
}

class ShellActivity : Activity() {
    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra("url") ?: SERVER

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = settings.userAgentString + " BrewShell/1"   // the server checks for this
            addJavascriptInterface(Bridge(), "BrewAndroid")
            webViewClient = object : WebViewClient() {
                // Links to other routes open a new native screen; this page stays put.
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (request.url.host != Uri.parse(SERVER).host) return false
                    visit(request.url.toString())
                    return true
                }

                override fun onPageFinished(view: WebView, loadedUrl: String) {
                    title = view.title
                    // Toy: a real app sends its FCM token here. The page posts it to /native/device.
                    if (isTaskRoot) sendToWeb(JSONObject(mapOf("type" to "push-token", "token" to "emulator-${UUID.randomUUID()}")))
                }
            }
        }
        setContentView(webView)

        if (isTaskRoot && savedInstanceState == null) {
            thread {
                runCatching { PathConfig.load() }
                runOnUiThread { webView.loadUrl(url) }
            }
        } else {
            webView.loadUrl(url)
        }
    }

    private fun visit(url: String) {
        val intent = Intent(this, ShellActivity::class.java).putExtra("url", url)
        when (PathConfig.presentation(Uri.parse(url).path ?: "/")) {
            "root"  -> { intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(intent) }
            "modal" -> { startActivity(intent); overridePendingTransition(android.R.anim.slide_in_left, android.R.anim.fade_out) }
            else    -> startActivity(intent)
        }
    }

    // Native → web. The page listens with data-on:brew:native__window.
    private fun sendToWeb(message: JSONObject) {
        webView.evaluateJavascript("window.dispatchEvent(new CustomEvent('brew:native', {detail: $message}))", null)
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    /** Web → native: window.BrewAndroid.postMessage(JSON.stringify(msg)). Runs on a binder thread. */
    inner class Bridge {
        @JavascriptInterface
        fun postMessage(json: String) {
            val msg = JSONObject(json)
            runOnUiThread {
                when (msg.optString("type")) {
                    "haptic" -> webView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    "notify" -> notify(msg.optString("title", "Brew Board"), msg.optString("body"))
                }
            }
        }
    }

    private fun notify(title: String, body: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("orders", "Orders", NotificationManager.IMPORTANCE_DEFAULT))
        val notification = android.app.Notification.Builder(this, "orders")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .build()
        manager.notify(body.hashCode(), notification)
    }
}
