package ru.avitomonitor

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
    data class Items(val items: List<Listing>, val pageTitle: String, val pageUrl: String) : ParseResult
    data class Captcha(val reason: String, val pageUrl: String) : ParseResult
    data class Error(val message: String) : ParseResult
}

data class Config(
    val url: String,
    val token: String,
    val chatId: String,
    val intervalSec: Int,
    val stopWords: List<String>,
    val priceMin: Long?,
    val priceMax: Long?,
    val sendPhotos: Boolean = true,
) {
    fun describeFilters(): String {
        val price = when {
            priceMin != null && priceMax != null -> "$priceMin – $priceMax ₽"
            priceMin != null -> "от $priceMin ₽"
            priceMax != null -> "до $priceMax ₽"
            else -> "любая"
        }
        val words = if (stopWords.isEmpty()) "нет" else stopWords.joinToString(", ")
        return "Цена: $price\nСтоп-слова: $words\nИнтервал: $intervalSec с (+15–40 с случайно)"
    }

    /** Возвращает причину отсева или null, если объявление проходит фильтры. */
    fun rejectReason(item: Listing): String? {
        val hay = "${item.title} ${item.description} ${item.params}".lowercase()
        stopWords.firstOrNull { hay.contains(it) }?.let { return "стоп-слово «$it»" }
        val p = item.priceValue ?: return null
        if (priceMin != null && p < priceMin) return "цена $p < $priceMin"
        if (priceMax != null && p > priceMax) return "цена $p > $priceMax"
        return null
    }
}
