package ru.avitomonitor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Отправка в Telegram Bot API (OkHttp используется только для api.telegram.org, не для Avito). */
object TelegramClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .build()

    suspend fun send(token: String, chatId: String, html: String, preview: Boolean = true): Result<Unit> =
        withContext(Dispatchers.IO) {
            if (token.isBlank() || chatId.isBlank()) {
                return@withContext Result.failure(IOException("не задан токен или chat id"))
            }
            var lastError: Exception = IOException("неизвестная ошибка")
            repeat(3) { attempt ->
                val body = FormBody.Builder()
                    .add("chat_id", chatId)
                    .add("text", html.take(4096))
                    .add("parse_mode", "HTML")
                    .add("disable_web_page_preview", (!preview).toString())
                    .build()
                val request = Request.Builder()
                    .url("https://api.telegram.org/bot$token/sendMessage")
                    .post(body)
                    .build()
                try {
                    http.newCall(request).execute().use { resp ->
                        val text = resp.body?.string().orEmpty()
                        if (resp.isSuccessful) return@withContext Result.success(Unit)
                        val json = runCatching { JSONObject(text) }.getOrNull()
                        val description = json?.optString("description").orEmpty()
                        if (resp.code == 429) {
                            val retry = json?.optJSONObject("parameters")?.optInt("retry_after", 5) ?: 5
                            lastError = IOException("Telegram 429: ждём $retry с")
                            delay(retry * 1000L + 500)
                        } else {
                            // 400/401/403 — повтор не поможет (неверный токен, chat id, HTML)
                            return@withContext Result.failure(IOException("Telegram ${resp.code}: $description"))
                        }
                    }
                } catch (e: IOException) {
                    lastError = IOException("сеть: ${e.javaClass.simpleName} ${e.message.orEmpty()}")
                    delay(3_000L * (attempt + 1))
                }
            }
            Result.failure(lastError)
        }

    fun formatListing(l: Listing): String = buildString {
        append("🆕 <b>").append(esc(l.title.ifBlank { "Без названия" })).append("</b>\n")
        if (l.price.isNotBlank()) append("💰 <b>").append(esc(l.price)).append("</b>\n")
        if (l.geo.isNotBlank()) append("📍 ").append(esc(l.geo)).append('\n')
        if (l.params.isNotBlank()) append("⚙️ ").append(esc(l.params.take(200))).append('\n')
        if (l.description.isNotBlank()) append('\n').append(esc(shorten(l.description, 400))).append('\n')
        append("\n<a href=\"").append(esc(l.url)).append("\">🔗 Открыть на Avito</a>")
    }

    private fun shorten(s: String, max: Int) = if (s.length <= max) s else s.take(max).trimEnd() + "…"

    fun esc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
