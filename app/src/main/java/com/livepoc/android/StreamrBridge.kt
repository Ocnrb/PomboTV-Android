package com.livepoc.android

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Headless WebView running the Streamr JS SDK as a pure binary transport.
 * Native → JS: evaluateJavascript with base64 payloads (order preserved: single
 * main-thread queue + per-kind promise chain on the JS side).
 * JS → Native: @JavascriptInterface callbacks (arrive on a WebView binder thread).
 */
class StreamrBridge(context: Context, private val listener: Listener) {

    interface Listener {
        fun onBridgeStatus(status: String)
        fun onBridgeConnected(address: String)
        /** Called on a background (JS bridge) thread. */
        fun onBridgeMessage(kind: String, payload: ByteArray)
        fun onBridgeError(message: String)
        /** Per-stream overlay info: {"video":{"proxy":N,"mesh":N},…} every ~5s. */
        fun onBridgeNetInfo(json: String) {}
        /** Live monitor: true = a broadcast is currently on air (audio flowing). */
        fun onBridgeLiveState(live: Boolean) {}
    }

    private val main = Handler(Looper.getMainLooper())
    private val webView: WebView
    private val bridgeHtml: String
    private val inFlightBytes = java.util.concurrent.atomic.AtomicLong(0)
    private val ackedBytes = java.util.concurrent.atomic.AtomicLong(0)

    @Volatile var connected = false
        private set

    /** Bytes accepted for publish but not yet drained by the network — the
     *  backpressure signal for the adaptive bitrate controller. */
    fun inFlight(): Long = maxOf(0L, inFlightBytes.get())

    /** Total de bytes que a rede JÁ drenou (publish resolvido) — base do tx=. */
    fun ackedTotal(): Long = ackedBytes.get()

    init {
        @SuppressLint("SetJavaScriptEnabled")
        webView = WebView(context.applicationContext).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // A página tem origem https:// (loadDataWithBaseURL) mas ligações a
            // nós relay locais são ws:// — sem isto o Chromium bloqueia (mixed content).
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            addJavascriptInterface(JsApi(), "Native")
            // SDK servido dos assets (sem CDN): o <script src="streamr-sdk.min.js">
            // da página é intercetado aqui — arranque mais rápido e offline-safe.
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    if (request.url.lastPathSegment == "streamr-sdk.min.js") {
                        return try {
                            WebResourceResponse("application/javascript", "utf-8",
                                context.applicationContext.assets.open("streamr-sdk.min.js"))
                        } catch (e: Exception) { null }
                    }
                    return null
                }
            }
            // JS console → logcat: without this, SDK load/runtime errors inside the
            // WebView are invisible (status just hangs at "a ligar…").
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                    Log.d("StreamrBridgeJS", "[${msg.messageLevel()}] ${msg.message()} (${msg.sourceId()}:${msg.lineNumber()})")
                    return true
                }
            }
        }
        webView.resumeTimers()
        bridgeHtml = context.assets.open("streamr_bridge.html").readBytes().toString(Charsets.UTF_8)
        // https base URL: some entry points reject a file:// / null Origin.
        webView.loadDataWithBaseURL("https://livepoc.local/", bridgeHtml, "text/html", "utf-8", null)
    }

    /**
     * Renasce o mundo JS (novo cliente Streamr) mantendo este objeto — usado
     * quando a rede muda (Wi-Fi↔dados): as ligações do nó morrem e não voltam.
     * O listener recebe onBridgeConnected quando o novo cliente ligar; quem
     * estiver a ver/transmitir re-subscreve nessa altura.
     */
    fun reconnect() {
        connected = false
        inFlightBytes.set(0)
        main.post {
            webView.loadDataWithBaseURL("https://livepoc.local/", bridgeHtml, "text/html", "utf-8", null)
        }
    }

    private inner class JsApi {
        @JavascriptInterface
        fun status(s: String) { main.post { listener.onBridgeStatus(s) } }

        @JavascriptInterface
        fun connected(addr: String) {
            connected = true
            main.post { listener.onBridgeConnected(addr) }
        }

        @JavascriptInterface
        fun message(kind: String, b64: String) {
            val bytes = try { Base64.decode(b64, Base64.NO_WRAP) } catch (e: Exception) { return }
            listener.onBridgeMessage(kind, bytes)
        }

        @JavascriptInterface
        fun error(msg: String) { main.post { listener.onBridgeError(msg) } }

        @JavascriptInterface
        fun netinfo(json: String) { main.post { listener.onBridgeNetInfo(json) } }

        @JavascriptInterface
        fun liveState(live: Boolean) { main.post { listener.onBridgeLiveState(live) } }

        @JavascriptInterface
        fun acked(kind: String, n: Int) {
            inFlightBytes.addAndGet(-n.toLong())
            ackedBytes.addAndGet(n.toLong())
        }

        /** O watchdog JS esgotou as recuperações — renascer a página inteira. */
        @JavascriptInterface
        fun needReload() {
            main.post { listener.onBridgeStatus("connection lost · restarting…") }
            reconnect()
        }
    }

    private fun js(script: String) {
        main.post { webView.evaluateJavascript(script, null) }
    }

    fun publish(kind: String, payload: ByteArray) {
        if (!connected) return
        inFlightBytes.addAndGet(payload.size.toLong())
        val b64 = Base64.encodeToString(payload, Base64.NO_WRAP)
        js("bridgePublish('$kind','$b64')")
    }

    /** 0 = forçar malha; 1-3 = nº de ligações proxy por direção. */
    fun setProxyCounts(pub: Int, sub: Int) = js("bridgeSetProxyCounts($pub,$sub)")

    /** Monitor de live ativo (subscrição leve ao áudio) — onBridgeLiveState. */
    fun monitorStart() = js("bridgeMonitorStart()")
    fun monitorStop() = js("bridgeMonitorStop()")

    fun subscribe(kind: String) = js("bridgeSubscribe('$kind')")
    fun unsubscribe(kind: String) = js("bridgeUnsubscribe('$kind')")
    fun join(kind: String) = js("bridgeJoin('$kind')")
    fun leave(kind: String) = js("bridgeLeave('$kind')")

    fun destroy() {
        main.post { webView.destroy() }
    }
}
