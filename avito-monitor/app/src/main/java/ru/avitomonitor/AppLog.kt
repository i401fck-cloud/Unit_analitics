package ru.avitomonitor

import android.util.Log
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
    private fun add(prefix: String, msg: String) {
        val line = "${fmt.format(Date())} $prefix $msg"
        Log.d("AvitoMonitor", line)
        _lines.value = (_lines.value + line).takeLast(MAX_LINES)
    }
}

/** Общее состояние между Activity (владелец WebView) и сервисом (владелец таймера). */
object ScannerHub {
    @Volatile var driver: WebViewDriver? = null
    val running = MutableStateFlow(false)
    val captcha = MutableStateFlow(false)
    val status = MutableStateFlow("Остановлен")
}
