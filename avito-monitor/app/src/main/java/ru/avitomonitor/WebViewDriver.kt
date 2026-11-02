package ru.avitomonitor

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.UUID
import kotlin.random.Random

/**
 * Управляет WebView, который находится на главном экране. Avito загружается настоящим
 * браузерным движком, а данные достаются из готового DOM скриптом assets/parser.js.
 * Все методы вызываются из сервиса и сами переключаются на главный поток.
 */
@SuppressLint("SetJavaScriptEnabled")
class WebViewDriver(private val webView: WebView) {

    private val parserJs: String =
        webView.context.assets.open("parser.js").bufferedReader().use { it.readText() }.trim()

    private val bridge = Bridge()
    @Volatile private var pageLoaded: CompletableDeferred<Unit>? = null

    init {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            mediaPlaybackRequiresUserGesture = true
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportMultipleWindows(false)
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        webView.addJavascriptInterface(bridge, "AndroidBridge")
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Не даём сайту открыть приложение Avito / market:// и т.п. — остаёмся в WebView.
                val scheme = request.url.scheme.orEmpty()
                return scheme != "http" && scheme != "https"
            }

            override fun onPageFinished(view: WebView, url: String?) {
                pageLoaded?.complete(Unit)
                CookieManager.getInstance().flush()
            }
        }
    }

    fun loadManually(url: String) = webView.loadUrl(url)

    /** Полный цикл: загрузка → ожидание рендера JS → скролл → парсинг. */
    suspend fun loadAndParse(url: String): ParseResult = withContext(Dispatchers.Main) {
        val loaded = CompletableDeferred<Unit>()
        pageLoaded = loaded
        webView.stopLoading()
        webView.loadUrl(url)

        if (withTimeoutOrNull(PAGE_TIMEOUT_MS) { loaded.await() } == null) {
            return@withContext ParseResult.Error("страница не загрузилась за ${PAGE_TIMEOUT_MS / 1000} с")
        }
        delay(Random.nextLong(3_000, 5_001)) // даём SPA дорендерить карточки
        humanScroll()
        runParser()
    }

    /** Парсинг текущей страницы без перезагрузки (используется во время ожидания капчи). */
    suspend fun probe(): ParseResult = withContext(Dispatchers.Main) { runParser() }

    private suspend fun humanScroll() {
        repeat(Random.nextInt(2, 5)) {
            val dy = Random.nextInt(250, 800)
            webView.evaluateJavascript("window.scrollBy({top:$dy,left:0,behavior:'smooth'});", null)
            delay(Random.nextLong(600, 1_800))
        }
        if (Random.nextInt(3) == 0) {
            webView.evaluateJavascript("window.scrollBy({top:-${Random.nextInt(100, 300)},behavior:'smooth'});", null)
            delay(Random.nextLong(400, 900))
        }
        delay(Random.nextLong(500, 1_200))
    }

    private suspend fun runParser(): ParseResult {
        val token = UUID.randomUUID().toString()
        val result = CompletableDeferred<String>()
        bridge.expect(token, result)
        webView.evaluateJavascript("$parserJs(\"$token\");", null)
        val json = withTimeoutOrNull(JS_TIMEOUT_MS) { result.await() }
            ?: return ParseResult.Error("JS-парсер не ответил")
        return decode(json)
    }

    private fun decode(json: String): ParseResult {
        val o = try { JSONObject(json) } catch (e: Exception) {
            return ParseResult.Error("битый JSON от парсера")
        }
        if (!o.optBoolean("ok")) return ParseResult.Error("ошибка JS: ${o.optString("error")}")
        val pageUrl = o.optString("url")
        if (o.optBoolean("captcha")) return ParseResult.Captcha(o.optString("reason"), pageUrl)

        val arr = o.optJSONArray("items")
        val items = ArrayList<Listing>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val j = arr.optJSONObject(i) ?: continue
                val id = j.optString("id")
                if (id.isBlank()) continue
                items += Listing(
                    id = id,
                    title = j.optString("title"),
                    price = j.optString("price"),
                    url = j.optString("url"),
                    description = j.optString("description"),
                    geo = j.optString("geo"),
                    params = j.optString("params"),
                )
            }
        }
        return ParseResult.Items(items, o.optString("title"), pageUrl)
    }

    /** Мост JS → Kotlin. Принимает только ответ на текущий запрос (проверка токена). */
    private class Bridge {
        @Volatile private var token: String? = null
        @Volatile private var target: CompletableDeferred<String>? = null

        fun expect(token: String, target: CompletableDeferred<String>) {
            this.token = token
            this.target = target
        }

        @JavascriptInterface
        fun onResult(json: String) {
            val t = token ?: return
            val got = try { JSONObject(json).optString("token") } catch (e: Exception) { return }
            if (got == t) target?.complete(json)
        }
    }

    companion object {
        private const val PAGE_TIMEOUT_MS = 45_000L
        private const val JS_TIMEOUT_MS = 15_000L
    }
}
