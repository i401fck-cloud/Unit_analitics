package ru.avitomonitor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Telegram Bot API через OkHttp (используется только для api.telegram.org, не для Avito). */
object TelegramClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Отдельный клиент для long polling getUpdates (запрос висит до 50 с). */
    private val pollHttp = http.newBuilder()
        .readTimeout(75, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    data class Update(
        val updateId: Long,
        val chatId: String,
        val text: String,
        val callbackId: String?,
    )

    // ------------------------------------------------------------------ базовый вызов

    private suspend fun call(
        token: String,
        method: String,
        body: RequestBody,
        client: OkHttpClient = http,
        attempts: Int = 3,
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        if (token.isBlank()) return@withContext Result.failure(IOException("не задан токен бота"))
        var lastError: Exception = IOException("неизвестная ошибка")
        repeat(attempts) { attempt ->
            val request = Request.Builder()
                .url("https://api.telegram.org/bot$token/$method")
                .post(body)
                .build()
            try {
                client.newCall(request).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    val json = runCatching { JSONObject(text) }.getOrNull()
                    if (resp.isSuccessful && json?.optBoolean("ok") == true) {
                        return@withContext Result.success(json)
                    }
                    val description = json?.optString("description").orEmpty()
                    if (resp.code == 429) {
                        val retry = json?.optJSONObject("parameters")?.optInt("retry_after", 5) ?: 5
                        lastError = IOException("Telegram 429: лимит, ждём $retry с")
                        delay(retry * 1000L + 500)
                    } else {
                        // 400/401/403 — повтор не поможет (неверный токен, chat id, HTML)
                        return@withContext Result.failure(IOException("Telegram ${resp.code}: $description"))
                    }
                }
            } catch (e: IOException) {
                lastError = IOException("сеть: ${e.javaClass.simpleName} ${e.message.orEmpty()}")
                if (attempt < attempts - 1) delay(3_000L * (attempt + 1))
            }
        }
        Result.failure(lastError)
    }

    // ------------------------------------------------------------------ отправка

    suspend fun send(
        token: String,
        chatId: String,
        html: String,
        preview: Boolean = false,
        keyboard: String? = null,
    ): Result<Unit> {
        if (chatId.isBlank()) return Result.failure(IOException("не задан chat id"))
        val body = FormBody.Builder()
            .add("chat_id", chatId)
            .add("text", html.take(4096))
            .add("parse_mode", "HTML")
            .add("disable_web_page_preview", (!preview).toString())
            .apply { if (keyboard != null) add("reply_markup", keyboard) }
            .build()
        return call(token, "sendMessage", body).map { }
    }

    suspend fun sendPhotoUrl(token: String, chatId: String, photoUrl: String, captionHtml: String, keyboard: String?): Result<Unit> {
        val body = FormBody.Builder()
            .add("chat_id", chatId)
            .add("photo", photoUrl)
            .add("caption", captionHtml)
            .add("parse_mode", "HTML")
            .apply { if (keyboard != null) add("reply_markup", keyboard) }
            .build()
        return call(token, "sendPhoto", body, attempts = 2).map { }
    }

    suspend fun sendPhotoBytes(token: String, chatId: String, jpeg: ByteArray, captionHtml: String, keyboard: String? = null): Result<Unit> {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId)
            .addFormDataPart("caption", captionHtml)
            .addFormDataPart("parse_mode", "HTML")
            .addFormDataPart("photo", "screen.jpg", jpeg.toRequestBody("image/jpeg".toMediaType()))
            .apply { if (keyboard != null) addFormDataPart("reply_markup", keyboard) }
            .build()
        return call(token, "sendPhoto", body, attempts = 2).map { }
    }

    suspend fun sendDocument(token: String, chatId: String, fileName: String, bytes: ByteArray, captionHtml: String): Result<Unit> {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId)
            .addFormDataPart("caption", captionHtml)
            .addFormDataPart("parse_mode", "HTML")
            .addFormDataPart("document", fileName, bytes.toRequestBody("text/plain".toMediaType()))
            .build()
        return call(token, "sendDocument", body, attempts = 2).map { }
    }

    /** Карточка объявления: фото + подпись + кнопка «Открыть». Если фото не прошло — текстом. */
    suspend fun sendListing(cfg: Config, item: Listing, tag: String?): Result<Unit> {
        val kb = keyboard(listOf(urlButton("🔗 Открыть на Avito", item.url)))
        if (cfg.attach != AttachMode.NONE && item.imageUrl.startsWith("http")) {
            val r = sendPhotoUrl(cfg.token, cfg.chatId, item.imageUrl, formatListing(item, 1024, tag), kb)
            if (r.isSuccess) return r
            AppLog.w("Фото не отправилось (${r.exceptionOrNull()?.message}), шлю текстом")
        }
        return send(cfg.token, cfg.chatId, formatListing(item, 4096, tag), preview = false, keyboard = kb)
    }

    // ------------------------------------------------------------------ кнопки под полем ввода

    const val BTN_STATUS = "📊 Статус"
    const val BTN_CHECK = "🔄 Проверить"
    const val BTN_PAUSE = "⏸ Пауза"
    const val BTN_RESUME = "▶️ Продолжить"
    const val BTN_MARKET = "💰 Цены рынка"
    const val BTN_SCREEN = "📸 Скрин"
    const val BTN_SEARCHES = "🔎 Поиски"
    const val BTN_SETTINGS = "⚙️ Настройки"

    /** Постоянная клавиатура над строкой ввода (заменяет команды). */
    fun replyKeyboard(): String {
        fun row(vararg t: String) = JSONArray().apply { t.forEach { put(JSONObject().put("text", it)) } }
        return JSONObject()
            .put(
                "keyboard",
                JSONArray()
                    .put(row(BTN_STATUS, BTN_CHECK))
                    .put(row(BTN_PAUSE, BTN_RESUME))
                    .put(row(BTN_MARKET, BTN_SCREEN))
                    .put(row(BTN_SEARCHES, BTN_SETTINGS)),
            )
            .put("resize_keyboard", true)
            .put("is_persistent", true)
            .put("input_field_placeholder", "Кнопки управления ниже")
            .toString()
    }

    fun formatMarket(search: Search, m: MarketSummary?, found: Int): String = buildString {
        append("💰 <b>").append(esc(search.name)).append("</b>\n")
        if (m == null) {
            append("Мало объявлений с ценой (на странице: ").append(found).append(") — оценка невозможна.")
            return@buildString
        }
        append("Медиана: <b>").append(money(m.median)).append("</b>\n")
        append("Средняя: ").append(money(m.mean)).append('\n')
        append("Типичный диапазон: ").append(money(m.p25)).append(" – ").append(money(m.p75)).append('\n')
        append("Мин / макс: ").append(money(m.min)).append(" / ").append(money(m.max)).append('\n')
        append("<i>По ").append(m.count).append(" свежим объявлениям")
        if (m.used < m.count) append(", без ").append(m.count - m.used).append(" выбросов")
        if (search.stopWords.isNotEmpty()) append(", без стоп-слов")
        append("</i>")
    }

    suspend fun answerCallback(token: String, callbackId: String, text: String = "") {
        val body = FormBody.Builder().add("callback_query_id", callbackId).add("text", text).build()
        call(token, "answerCallbackQuery", body, attempts = 1)
    }

    /** Меню команд в Telegram (кнопка «/» рядом с полем ввода). */
    suspend fun setCommands(token: String): Result<Unit> {
        val cmds = JSONArray()
        listOf(
            "start" to "Показать кнопки управления",
            "status" to "Состояние и статистика",
            "help" to "Помощь",
        ).forEach { (c, d) -> cmds.put(JSONObject().put("command", c).put("description", d)) }
        val body = FormBody.Builder().add("commands", cmds.toString()).build()
        return call(token, "setMyCommands", body, attempts = 1).map { }
    }

    /** Long polling. offset = -1 — получить только последнее обновление (пропуск истории). */
    suspend fun getUpdates(token: String, offset: Long, timeoutSec: Int): Result<List<Update>> {
        val body = FormBody.Builder()
            .add("offset", offset.toString())
            .add("timeout", timeoutSec.toString())
            .add("allowed_updates", """["message","callback_query"]""")
            .build()
        return call(token, "getUpdates", body, client = pollHttp, attempts = 1).map { json ->
            val arr = json.optJSONArray("result") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                val u = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = u.optLong("update_id")
                val msg = u.optJSONObject("message")
                val cb = u.optJSONObject("callback_query")
                when {
                    msg != null -> Update(
                        id,
                        msg.optJSONObject("chat")?.optLong("id")?.toString().orEmpty(),
                        msg.optString("text"),
                        null,
                    )
                    cb != null -> Update(
                        id,
                        cb.optJSONObject("message")?.optJSONObject("chat")?.optLong("id")?.toString().orEmpty(),
                        cb.optString("data"),
                        cb.optString("id"),
                    )
                    else -> Update(id, "", "", null)
                }
            }
        }
    }

    // ------------------------------------------------------------------ форматирование

    fun urlButton(text: String, url: String): JSONObject = JSONObject().put("text", text).put("url", url)

    fun cbButton(text: String, data: String): JSONObject = JSONObject().put("text", text).put("callback_data", data)

    fun keyboard(vararg rows: List<JSONObject>): String {
        val kb = JSONArray()
        rows.forEach { row -> kb.put(JSONArray().apply { row.forEach { put(it) } }) }
        return JSONObject().put("inline_keyboard", kb).toString()
    }

    /** HTML-карточка объявления, уложенная в [limit] символов (1024 для подписи к фото). */
    fun formatListing(l: Listing, limit: Int, tag: String? = null): String {
        val head = buildString {
            if (!tag.isNullOrBlank()) append("🔎 ").append(esc(tag.take(40))).append('\n')
            append("🆕 <b>").append(esc(l.title.ifBlank { "Без названия" }.take(150))).append("</b>\n")
            if (l.price.isNotBlank()) append("💰 <b>").append(esc(l.price)).append("</b>\n")
            if (l.geo.isNotBlank()) append("📍 ").append(esc(l.geo.take(120))).append('\n')
            if (l.params.isNotBlank()) append("⚙️ ").append(esc(l.params.take(160))).append('\n')
        }
        val room = limit - head.length - 8
        if (l.description.isBlank() || room < 40) return head.trimEnd()
        val desc = shorten(l.description, minOf(400, room))
        // экранирование удлиняет текст — подрезаем, пока не влезет
        var escaped = esc(desc)
        var cut = desc.length
        while (escaped.length > room && cut > 20) {
            cut -= 20
            escaped = esc(shorten(desc, cut))
        }
        return "$head\n<i>$escaped</i>"
    }

    private fun shorten(s: String, max: Int) = if (s.length <= max) s else s.take(max - 1).trimEnd() + "…"

    fun esc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
