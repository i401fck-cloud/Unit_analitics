package ru.avitomonitor

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import ru.avitomonitor.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var driver: WebViewDriver
    private var webVisible = false

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) AppLog.w("Нет разрешения на уведомления — алерты о капче будут только в Telegram")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)
        Notifier.createChannels(this)

        fillFields()
        driver = WebViewDriver(b.webView)
        ScannerHub.driver = driver

        b.btnStartStop.setOnClickListener { toggleService() }
        b.btnToggleWeb.setOnClickListener { setWebVisible(!webVisible) }
        b.btnClear.setOnClickListener { confirmClear() }
        b.btnTestTg.setOnClickListener { testTelegram() }

        onBackPressedDispatcher.addCallback(this) {
            when {
                webVisible && b.webView.canGoBack() -> b.webView.goBack()
                webVisible -> setWebVisible(false)
                // Не закрываем Activity, иначе пропадёт WebView — просто уходим на задний план
                else -> moveTaskToBack(true)
            }
        }

        setWebVisible(false)
        // Сразу открываем Avito, чтобы можно было авторизоваться / пройти проверку до старта
        driver.loadManually(prefs.url.ifBlank { "https://m.avito.ru" })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { AppLog.lines.collect { renderLog(it) } }
                launch {
                    combine(ScannerHub.running, ScannerHub.captcha, ScannerHub.status) { r, c, s -> Triple(r, c, s) }
                        .collect { (running, captcha, status) -> renderState(running, captcha, status) }
                }
            }
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Сервис мог остаться включённым после перезапуска процесса
        if (prefs.serviceEnabled && !ScannerHub.running.value) startScanner(validate = false)
    }

    override fun onDestroy() {
        if (ScannerHub.driver === driver) ScannerHub.driver = null
        b.webView.destroy()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ настройки

    private fun fillFields() {
        b.etUrl.setText(prefs.url)
        b.etToken.setText(prefs.token)
        b.etChatId.setText(prefs.chatId)
        b.etInterval.setText(prefs.intervalSec.toString())
        b.etStopWords.setText(prefs.stopWords)
        b.etPriceMin.setText(prefs.priceMin)
        b.etPriceMax.setText(prefs.priceMax)
    }

    /** Сохраняет поля. Возвращает false, если что-то обязательное не заполнено. */
    private fun saveFields(requireAll: Boolean): Boolean {
        val url = Prefs.normalizeUrl(b.etUrl.text.toString())
        val token = b.etToken.text.toString().trim()
        val chat = b.etChatId.text.toString().trim()
        var interval = b.etInterval.text.toString().toIntOrNull() ?: Prefs.DEFAULT_INTERVAL
        if (interval < Prefs.MIN_INTERVAL) {
            interval = Prefs.MIN_INTERVAL
            toast("Минимальный интервал — ${Prefs.MIN_INTERVAL} с")
        }
        b.etUrl.setText(url)
        b.etInterval.setText(interval.toString())

        prefs.url = url
        prefs.token = token
        prefs.chatId = chat
        prefs.intervalSec = interval
        prefs.stopWords = b.etStopWords.text.toString().trim()
        prefs.priceMin = b.etPriceMin.text.toString().trim()
        prefs.priceMax = b.etPriceMax.text.toString().trim()

        if (!requireAll) return true
        val error = when {
            url.isBlank() || !Uri.parse(url).host.orEmpty().endsWith("avito.ru") -> "Укажите ссылку поиска avito.ru"
            token.isBlank() -> "Укажите Telegram Bot Token"
            chat.isBlank() -> "Укажите Telegram Chat ID"
            else -> null
        }
        if (error != null) toast(error)
        return error == null
    }

    private fun setFieldsEnabled(enabled: Boolean) {
        listOf(b.etUrl, b.etToken, b.etChatId, b.etInterval, b.etStopWords, b.etPriceMin, b.etPriceMax)
            .forEach { it.isEnabled = enabled }
    }

    // ------------------------------------------------------------------ кнопки

    private fun toggleService() {
        if (ScannerHub.running.value) {
            prefs.serviceEnabled = false
            stopService(Intent(this, AvitoParserService::class.java))
        } else {
            startScanner(validate = true)
        }
    }

    private fun startScanner(validate: Boolean) {
        if (validate && !saveFields(requireAll = true)) return
        requestIgnoreBatteryOptimizations()
        ContextCompat.startForegroundService(this, Intent(this, AvitoParserService::class.java))
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("Очистить базу ID?")
            .setMessage("Следующий проход снова будет «тихим»: текущие объявления попадут в базу без отправки.")
            .setPositiveButton("Очистить") { _, _ ->
                SeenStore.get(this).clear()
                prefs.seededUrl = ""
                AppLog.ok("База ID очищена")
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun testTelegram() {
        saveFields(requireAll = false)
        val cfg = prefs.config()
        lifecycleScope.launch {
            TelegramClient.send(cfg.token, cfg.chatId, "🔔 <b>Avito Monitor</b>: тестовое сообщение. Связь есть!")
                .onSuccess { AppLog.ok("Тестовое сообщение отправлено"); toast("Отправлено") }
                .onFailure { AppLog.e("Telegram: ${it.message}"); toast("Ошибка: ${it.message}") }
        }
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimizations() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            AppLog.w("Отключите оптимизацию батареи для приложения вручную в настройках")
        }
    }

    // ------------------------------------------------------------------ WebView / экран

    private fun setWebVisible(visible: Boolean) {
        webVisible = visible
        val lp = b.webContainer.layoutParams as LinearLayout.LayoutParams
        if (visible) {
            b.settingsPanel.visibility = View.GONE
            lp.height = 0
            lp.weight = 1f
            b.webOverlay.visibility = View.GONE
            b.btnToggleWeb.text = "Скрыть WebView"
        } else {
            b.settingsPanel.visibility = View.VISIBLE
            lp.height = (160 * resources.displayMetrics.density).toInt()
            lp.weight = 0f
            b.webOverlay.visibility = View.VISIBLE
            b.btnToggleWeb.text = "Показать WebView"
        }
        b.webContainer.layoutParams = lp
        applyScreenMode(ScannerHub.running.value)
    }

    /**
     * «Вечный экран»: пока сканер работает, экран не гаснет (WebView рендерится корректно),
     * а когда WebView скрыт — яркость опускается до минимума для экономии батареи.
     */
    private fun applyScreenMode(running: Boolean) {
        if (running) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val attrs = window.attributes
        attrs.screenBrightness = if (running && !webVisible) 0.01f
        else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = attrs
    }

    private fun renderState(running: Boolean, captcha: Boolean, status: String) {
        b.btnStartStop.text = if (running) "Стоп" else "Старт"
        b.btnStartStop.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (running) R.color.red else R.color.green)
        )
        b.tvStatus.text = status
        b.tvStatus.setTextColor(ContextCompat.getColor(this, if (captcha) R.color.red else R.color.text))
        setFieldsEnabled(!running)
        // При капче сразу показываем WebView, чтобы её можно было пройти
        if (captcha && !webVisible) setWebVisible(true) else applyScreenMode(running)
    }

    private fun renderLog(lines: List<String>) {
        b.tvLog.text = lines.joinToString("\n")
        b.logScroll.post { b.logScroll.scrollTo(0, b.tvLog.bottom) }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
