package ru.avitomonitor

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.coroutines.resume
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

    /** Колбэки для UI (вызываются на главном потоке). */
    var onProgress: ((Int) -> Unit)? = null
    var onUrl: ((String) -> Unit)? = null
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
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                onProgress?.invoke(newProgress)
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Не даём сайту открыть приложение Avito / market:// и т.п. — остаёмся в WebView.
                val scheme = request.url.scheme.orEmpty()
                return scheme != "http" && scheme != "https"
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                url?.let { onUrl?.invoke(it) }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                pageLoaded?.complete(Unit)
                CookieManager.getInstance().flush()
            }
        }
    }

    fun loadManually(url: String) = webView.loadUrl(url)

    /** Снимок видимой части WebView в JPEG (для /screen и алерта о капче). */
    suspend fun screenshot(): ByteArray? = withContext(Dispatchers.Main) {
        captureBitmap()?.let { toJpeg(it) }
    }

    /**
     * Скриншот одной карточки из выдачи: прокручиваем к ней, ждём подгрузку фото и вырезаем
     * её область. Карточки запоминает parser.js при последнем парсинге (window.__amc).
     */
    suspend fun screenshotCard(id: String): ByteArray? = withContext(Dispatchers.Main) {
        val safeId = id.filter { it.isDigit() }
        val found = evalJs(
            "(function(){var m=window.__amc,e=m&&m['$safeId'];" +
                "if(!e||!e.isConnected)return false;e.scrollIntoView({block:'center'});return true;})()"
        )
        if (found != "true") return@withContext null
        delay(Random.nextLong(700, 1_200)) // догрузка ленивых картинок
        val rectJson = evalJs(
            "(function(){var e=window.__amc['$safeId'];if(!e)return null;var r=e.getBoundingClientRect();" +
                "return {x:r.left,y:r.top,w:r.width,h:r.height,vw:window.innerWidth};})()"
        )
        val r = runCatching { JSONObject(rectJson.orEmpty()) }.getOrNull() ?: return@withContext null
        val full = captureBitmap() ?: return@withContext null
        val vw = r.optDouble("vw", 0.0)
        if (vw <= 0) return@withContext toJpeg(full)
        val f = webView.width / vw // CSS-пиксели → пиксели WebView
        val pad = 6 * f
        val left = ((r.optDouble("x") * f) - pad).toInt().coerceIn(0, full.width - 1)
        val top = ((r.optDouble("y") * f) - pad).toInt().coerceIn(0, full.height - 1)
        val right = ((r.optDouble("x") + r.optDouble("w")) * f + pad).toInt().coerceIn(left + 1, full.width)
        val bottom = ((r.optDouble("y") + r.optDouble("h")) * f + pad).toInt().coerceIn(top + 1, full.height)
        if (right - left < 60 || bottom - top < 60) return@withContext toJpeg(full)
        val crop = Bitmap.createBitmap(full, left, top, right - left, bottom - top)
        if (crop !== full) full.recycle()
        toJpeg(crop)
    }

    /** Открывает страницу объявления и снимает её верх (фото, название, цена). */
    suspend fun screenshotPage(url: String): ByteArray? = withContext(Dispatchers.Main) {
        val loaded = CompletableDeferred<Unit>()
        pageLoaded = loaded
        webView.stopLoading()
        webView.loadUrl(url)
        if (withTimeoutOrNull(PAGE_TIMEOUT_MS) { loaded.await() } == null) return@withContext null
        delay(Random.nextLong(2_500, 4_000))
        if (runParser() is ParseResult.Captcha) return@withContext null
        webView.evaluateJavascript("window.scrollTo(0,0);", null)
        delay(400)
        captureBitmap()?.let { toJpeg(it) }
    }

    private fun captureBitmap(): Bitmap? {
        val w = webView.width
        val h = webView.height
        if (w <= 0 || h <= 0) return null
        return try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { webView.draw(Canvas(it)) }
        } catch (e: Throwable) {
            null
        }
    }

    private suspend fun toJpeg(src: Bitmap): ByteArray? {
        val scale = minOf(1f, 1080f / src.width)
        val bmp = if (scale < 1f) {
            Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true)
                .also { if (it !== src) src.recycle() }
        } else src
        return withContext(Dispatchers.Default) {
            try {
                ByteArrayOutputStream().use { out ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
                    out.toByteArray()
                }
            } catch (e: Throwable) {
                null
            } finally {
                bmp.recycle()
            }
        }
    }

    private suspend fun evalJs(script: String): String? = suspendCancellableCoroutine { cont ->
        webView.evaluateJavascript(script) { if (cont.isActive) cont.resume(it) }
    }

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
                    imageUrl = j.optString("image"),
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
