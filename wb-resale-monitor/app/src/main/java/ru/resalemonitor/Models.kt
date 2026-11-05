package ru.resalemonitor

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.roundToLong

data class Listing(
    val id: String,
    val title: String,
    val price: String,
    val url: String,
    val description: String,
    val geo: String,
    val params: String,
    val imageUrl: String = "",
) {
    /** Числовая цена из строки вида "12 500 ₽"; null, если цены нет ("Договорная" и т.п.). */
    val priceValue: Long?
        get() = price.filter { it.isDigit() }.takeIf { it.isNotEmpty() && it.length <= 12 }?.toLongOrNull()
}

sealed interface ParseResult {
    data class Items(
        val items: List<Listing>,
        val pageTitle: String,
        val pageUrl: String,
        val diag: String? = null,
    ) : ParseResult
    data class Captcha(val reason: String, val pageUrl: String) : ParseResult
    data class Error(val message: String) : ParseResult
}

/** Один поиск: своя ссылка, название и фильтры. */
data class Search(
    val id: String,
    val name: String,
    val url: String,
    val priceMin: Long? = null,
    val priceMax: Long? = null,
    val stopWords: List<String> = emptyList(),
    val enabled: Boolean = true,
) {
    fun stopWordHit(item: Listing): String? {
        if (stopWords.isEmpty()) return null
        val hay = "${item.title} ${item.description} ${item.params}".lowercase()
        return stopWords.firstOrNull { hay.contains(it) }
    }

    /** Причина отсева или null, если объявление проходит фильтры. */
    fun rejectReason(item: Listing): String? {
        stopWordHit(item)?.let { return "стоп-слово «$it»" }
        val p = item.priceValue ?: return null
        if (priceMin != null && p < priceMin) return "цена $p < $priceMin"
        if (priceMax != null && p > priceMax) return "цена $p > $priceMax"
        return null
    }

    fun priceText(): String = when {
        priceMin != null && priceMax != null -> "${money(priceMin)} – ${money(priceMax)}"
        priceMin != null -> "от ${money(priceMin)}"
        priceMax != null -> "до ${money(priceMax)}"
        else -> "любая цена"
    }

    fun wordsText(): String = if (stopWords.isEmpty()) "нет стоп-слов" else stopWords.joinToString(", ")

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("url", url)
        .put("priceMin", priceMin ?: JSONObject.NULL)
        .put("priceMax", priceMax ?: JSONObject.NULL)
        .put("stopWords", JSONArray(stopWords))
        .put("enabled", enabled)

    companion object {
        fun newId(): String = (1..6).map { "abcdefghijkmnpqrstuvwxyz23456789".random() }.joinToString("")

        fun fromJson(o: JSONObject): Search? {
            val url = o.optString("url")
            if (url.isBlank()) return null
            val words = o.optJSONArray("stopWords")
            return Search(
                id = o.optString("id").ifBlank { newId() },
                name = o.optString("name").ifBlank { guessName(url) },
                url = url,
                priceMin = if (o.isNull("priceMin")) null else o.optLong("priceMin"),
                priceMax = if (o.isNull("priceMax")) null else o.optLong("priceMax"),
                stopWords = (0 until (words?.length() ?: 0)).map { words!!.optString(it) }.filter { it.isNotBlank() },
                enabled = o.optBoolean("enabled", true),
            )
        }

        fun parseWords(raw: String): List<String> =
            raw.split(',', ';', '\n').map { it.trim().lowercase() }.filter { it.isNotEmpty() }

        /** Название по ссылке: текст запроса (q=) или последний сегмент пути. */
        fun guessName(url: String): String {
            val u = Uri.parse(url)
            (u.getQueryParameter("search") ?: u.getQueryParameter("q"))
                ?.takeIf { it.isNotBlank() }?.let { return it.replaceFirstChar { c -> c.uppercase() }.take(40) }
            val seg = u.pathSegments.lastOrNull { it.isNotBlank() } ?: return "Поиск"
            return seg.replace('_', ' ').replace('-', ' ').replaceFirstChar { it.uppercase() }.take(40)
        }
    }
}

data class Config(
    val token: String,
    val chatId: String,
    val intervalSec: Int,
    val attach: AttachMode = AttachMode.CARD,
    val searches: List<Search> = emptyList(),
) {
    val active: List<Search> get() = searches.filter { it.enabled && it.url.isNotBlank() }
}

/** Что прикладывать к найденному объявлению в Telegram. */
enum class AttachMode(val key: String, val title: String) {
    CARD("card", "скриншот карточки"),
    PAGE("page", "скриншот страницы объявления"),
    PHOTO("photo", "фото из объявления"),
    NONE("none", "только текст");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: CARD
    }
}

/** Сводка цен по объявлениям с первой страницы (самые свежие — сортировка по дате). */
data class MarketSummary(
    val count: Int,
    val used: Int,
    val median: Long,
    val mean: Long,
    val p25: Long,
    val p75: Long,
    val min: Long,
    val max: Long,
) {
    companion object {
        /** Стоп-слова учитываются (битые/запчасти не портят оценку), вилка цен — нет. */
        fun of(items: List<Listing>, search: Search?): MarketSummary? {
            val prices = items
                .filter { search?.stopWordHit(it) == null }
                .mapNotNull { it.priceValue }
                .filter { it > 0 }
                .sorted()
            if (prices.size < 3) return null
            val q1 = pct(prices, 0.25)
            val q3 = pct(prices, 0.75)
            val iqr = q3 - q1
            // Отбрасываем выбросы (аксессуары за 500 ₽, опечатки на 10 млн) по правилу 1.5·IQR
            val core = prices.filter { it >= q1 - 1.5 * iqr && it <= q3 + 1.5 * iqr }.ifEmpty { prices }
            return MarketSummary(
                count = prices.size,
                used = core.size,
                median = pct(core, 0.5).roundToLong(),
                mean = core.average().roundToLong(),
                p25 = q1.roundToLong(),
                p75 = q3.roundToLong(),
                min = prices.first(),
                max = prices.last(),
            )
        }

        private fun pct(sorted: List<Long>, p: Double): Double {
            if (sorted.size == 1) return sorted[0].toDouble()
            val idx = p * (sorted.size - 1)
            val lo = idx.toInt()
            val hi = minOf(lo + 1, sorted.size - 1)
            return sorted[lo] + (sorted[hi] - sorted[lo]) * (idx - lo)
        }
    }
}

private val moneyFormat = DecimalFormat("#,###", DecimalFormatSymbols(Locale.US).apply { groupingSeparator = ' ' })

fun money(v: Long): String = moneyFormat.format(v) + " ₽"
