package ru.avitomonitor

import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Живой лог событий, который отображается внизу экрана. */
object AppLog {
    private const val MAX_LINES = 300
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    fun i(msg: String) = add("·", msg)
    fun ok(msg: String) = add("✓", msg)
    fun w(msg: String) = add("!", msg)
    fun e(msg: String) = add("✗", msg)

    @Synchronized
    fun clear() {
        _lines.value = emptyList()
    }

    @Synchronized
    private fun add(prefix: String, msg: String) {
        val line = "${fmt.format(Date())} $prefix $msg"
        Log.d("AvitoMonitor", line)
        _lines.value = (_lines.value + line).takeLast(MAX_LINES)
    }
}

data class Stats(
    val cycles: Int = 0,
    val lastFound: Int = 0,
    val sentTotal: Int = 0,
    val inBase: Int = 0,
    val captchas: Int = 0,
    val lastCheck: Long = 0,
    val nextCheck: Long = 0,
    val busy: Boolean = false,
)

/** Последний результат по одному поиску (для обзора и расчёта рыночной цены). */
data class SearchStat(
    val found: Int = 0,
    val items: List<Listing> = emptyList(),
    val checkedAt: Long = 0,
    val sent: Int = 0,
)

/** Общее состояние между Activity (владелец WebView), сервисом (таймер) и Telegram-командами. */
object ScannerHub {
    @Volatile var driver: WebViewDriver? = null
    val running = MutableStateFlow(false)
    val captcha = MutableStateFlow(false)
    val paused = MutableStateFlow(false)
    val status = MutableStateFlow("Остановлен")
    val stats = MutableStateFlow(Stats())
    val searchStats = MutableStateFlow<Map<String, SearchStat>>(emptyMap())

    /** Сигнал «проверить сейчас»: прерывает ожидание между циклами. */
    val wake = Channel<Unit>(Channel.CONFLATED)

    fun checkNow() {
        wake.trySend(Unit)
    }
}
