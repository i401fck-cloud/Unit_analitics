package ru.avitomonitor

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.avitomonitor.databinding.ActivityMainBinding
import ru.avitomonitor.databinding.DialogSearchBinding
import ru.avitomonitor.databinding.ItemSearchBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var driver: WebViewDriver

    private var isWide: Boolean? = null
    private var tab = TAB_OVERVIEW
    private var nightOn = false
    private var lastCaptcha = false
    private var lastRunning = false
    private var aodJob: Job? = null

    private val handler = Handler(Looper.getMainLooper())
    private val autoNight = Runnable {
        if (ScannerHub.running.value && prefs.autoNight && !ScannerHub.captcha.value) setNight(true)
    }

    /** Изменения из Telegram-команд (/price, /words, /interval, /url) сразу видны в полях. */
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            Prefs.KEY_SEARCHES -> {
                renderSearchList()
                renderOverviewSearches()
                updateSubtitle()
            }
            "interval" -> setField(b.etInterval, prefs.intervalSec.toString())
            "attach_mode" -> showAttachMode(prefs.attachMode)
        }
    }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) AppLog.w("Нет разрешения на уведомления — алерты о капче будут только в Telegram")
        }

    // ------------------------------------------------------------------ жизненный цикл

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.main) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, max(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }

        prefs = Prefs(this)
        Notifier.createChannels(this)
        fillFields()
        updateSubtitle()

        driver = WebViewDriver(b.webView)
        ScannerHub.driver = driver
        driver.onProgress = { p ->
            b.webProgress.visibility = if (p in 1..99) View.VISIBLE else View.INVISIBLE
            b.webProgress.setProgressCompat(p, true)
        }
        driver.onUrl = { b.tvWebUrl.text = it.removePrefix("https://").removePrefix("www.") }
        b.webCard.clipToOutline = true

        setupButtons()
        applyLayoutMode()

        onBackPressedDispatcher.addCallback(this) {
            when {
                nightOn -> setNight(false)
                tab == TAB_BROWSER && b.webView.canGoBack() -> b.webView.goBack()
                tab != TAB_OVERVIEW -> b.tabs.check(R.id.tabOverview)
                // Не закрываем Activity, иначе пропадёт WebView — просто уходим на задний план
                else -> moveTaskToBack(true)
            }
        }

        // Сразу открываем Avito, чтобы можно было войти в аккаунт / пройти проверку до старта
        if (!ScannerHub.running.value) driver.loadManually(homeUrl())

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { AppLog.lines.collect { renderLog(it) } }
                launch { ScannerHub.searchStats.collect { renderOverviewSearches() } }
                launch {
                    combine(
                        ScannerHub.running, ScannerHub.captcha, ScannerHub.paused,
                        ScannerHub.status, ScannerHub.stats,
                    ) { r, c, p, s, st -> UiState(r, c, p, s, st) }.collect { renderState(it) }
                }
                launch {
                    while (isActive) {
                        renderCountdown()
                        delay(1_000)
                    }
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

    override fun onStart() {
        super.onStart()
        prefs.registerListener(prefListener)
    }

    override fun onStop() {
        prefs.unregisterListener(prefListener)
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (ScannerHub.driver === driver) ScannerHub.driver = null
        b.webView.destroy()
        super.onDestroy()
    }

    /** Складывание/раскладывание Find N2 и разделённый экран не пересоздают Activity (см. манифест). */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyLayoutMode()
        if (nightOn) b.nightOverlay.post { moveAodBox() }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) scheduleAutoNight()
        return super.dispatchTouchEvent(ev)
    }

    // ------------------------------------------------------------------ адаптивная раскладка

    private fun applyLayoutMode() {
        val wide = resources.configuration.screenWidthDp >= WIDE_DP
        if (wide == isWide) return
        isWide = wide

        (b.webCard.parent as? ViewGroup)?.removeView(b.webCard)
        if (wide) {
            // Внутренний экран: слева управление, справа браузер
            b.panes.orientation = LinearLayout.HORIZONTAL
            b.leftPane.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            b.panes.addView(
                b.webCard,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.15f).apply { marginStart = dp(12) },
            )
            b.tabBrowser.isVisible = false
            if (tab == TAB_BROWSER) b.tabs.check(R.id.tabOverview)
        } else {
            // Внешний экран / разделённый экран: вкладки, браузер под панелями
            b.panes.orientation = LinearLayout.VERTICAL
            b.leftPane.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            )
            b.contentArea.addView(
                b.webCard, 0,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
            b.tabBrowser.isVisible = true
        }
        showTab(tab)
    }

    private fun showTab(t: Int) {
        tab = t
        b.overviewPanel.isVisible = t == TAB_OVERVIEW
        b.settingsPanel.isVisible = t == TAB_SETTINGS
        if (t == TAB_SETTINGS) updateBaseInfo()
        if (t != TAB_SETTINGS) hideKeyboard()
    }

    // ------------------------------------------------------------------ кнопки

    private fun setupButtons() {
        b.tabs.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            showTab(
                when (id) {
                    R.id.tabBrowser -> TAB_BROWSER
                    R.id.tabSettings -> TAB_SETTINGS
                    else -> TAB_OVERVIEW
                }
            )
        }
        b.tabs.check(R.id.tabOverview)

        b.btnStartStop.setOnClickListener { toggleService() }
        b.btnPause.setOnClickListener {
            val pause = !ScannerHub.paused.value
            ScannerHub.paused.value = pause
            ScannerHub.checkNow()
            AppLog.i(if (pause) "Пауза" else "Продолжаю")
        }
        b.btnCheckNow.setOnClickListener {
            ScannerHub.checkNow()
            toast("Проверяю сейчас")
        }
        b.btnNight.setOnClickListener { setNight(true) }
        b.nightOverlay.setOnClickListener { setNight(false) }
        b.btnClearLog.setOnClickListener { AppLog.clear() }

        b.btnWebBack.setOnClickListener { if (b.webView.canGoBack()) b.webView.goBack() }
        b.btnWebReload.setOnClickListener { b.webView.reload() }
        b.btnWebHome.setOnClickListener { driver.loadManually(homeUrl()) }
        b.btnAddSearch.setOnClickListener { editSearch(null) }

        b.btnSave.setOnClickListener {
            if (saveFields(requireAll = false)) {
                toast(if (ScannerHub.running.value) "Сохранено — применится со следующей проверки" else "Сохранено")
                hideKeyboard()
            }
        }
        b.btnClear.setOnClickListener { confirmClear() }
        b.btnTestTg.setOnClickListener { testTelegram() }
        b.attachGroup.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val mode = when (id) {
                R.id.attachPage -> AttachMode.PAGE
                R.id.attachPhoto -> AttachMode.PHOTO
                R.id.attachNone -> AttachMode.NONE
                else -> AttachMode.CARD
            }
            if (prefs.attachMode != mode) prefs.attachMode = mode
            renderAttachHint(mode)
        }
        b.switchAutoNight.setOnCheckedChangeListener { _, c ->
            prefs.autoNight = c
            scheduleAutoNight()
        }
    }

    private fun toggleService() {
        if (ScannerHub.running.value) {
            prefs.serviceEnabled = false
            stopService(Intent(this, AvitoParserService::class.java))
        } else {
            startScanner(validate = true)
        }
    }

    private fun startScanner(validate: Boolean) {
        if (validate && !saveFields(requireAll = true)) {
            b.tabs.check(R.id.tabSettings)
            return
        }
        requestIgnoreBatteryOptimizations()
        ContextCompat.startForegroundService(this, Intent(this, AvitoParserService::class.java))
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Очистить базу ID?")
            .setMessage("Следующий проход снова будет «тихим»: текущие объявления попадут в базу без отправки.")
            .setPositiveButton("Очистить") { _, _ ->
                SeenStore.get(this).clear()
                prefs.clearSeeded()
                ScannerHub.stats.update { it.copy(inBase = 0) }
                updateBaseInfo()
                AppLog.ok("База ID очищена")
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun testTelegram() {
        if (!saveFields(requireAll = false)) return
        val cfg = prefs.config()
        lifecycleScope.launch {
            TelegramClient.send(
                cfg.token, cfg.chatId,
                "🔔 <b>Avito Monitor</b>: тестовое сообщение. Связь есть!\n/help — команды управления",
            )
                .onSuccess { AppLog.ok("Тестовое сообщение отправлено"); toast("Отправлено") }
                .onFailure { AppLog.e("Telegram: ${it.message}"); toast("Ошибка: ${it.message}") }
        }
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimizations() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            AppLog.w("Отключите оптимизацию батареи для приложения вручную в настройках")
        }
    }

    // ------------------------------------------------------------------ настройки

    private fun fillFields() {
        setField(b.etToken, prefs.token)
        setField(b.etChatId, prefs.chatId)
        setField(b.etInterval, prefs.intervalSec.toString())
        showAttachMode(prefs.attachMode)
        b.switchAutoNight.isChecked = prefs.autoNight
        renderSearchList()
    }

    private fun showAttachMode(mode: AttachMode) {
        val id = when (mode) {
            AttachMode.CARD -> R.id.attachCard
            AttachMode.PAGE -> R.id.attachPage
            AttachMode.PHOTO -> R.id.attachPhoto
            AttachMode.NONE -> R.id.attachNone
        }
        if (b.attachGroup.checkedButtonId != id) b.attachGroup.check(id)
        renderAttachHint(mode)
    }

    private fun renderAttachHint(mode: AttachMode) {
        b.tvAttachHint.text = when (mode) {
            AttachMode.CARD -> "Скриншот карточки из выдачи + описание + ссылка. Быстро, без лишних запросов."
            AttachMode.PAGE -> "Открывает объявление и присылает скрин его страницы. Подробнее, но медленнее и чаще ведёт к капче."
            AttachMode.PHOTO -> "Главное фото из объявления + описание + ссылка."
            AttachMode.NONE -> "Только текст и ссылка."
        }
    }

    private fun setField(et: EditText, value: String) {
        if (et.text?.toString() != value) et.setText(value)
    }

    /** Сохраняет поля (сервис подхватывает их на следующем цикле). */
    private fun saveFields(requireAll: Boolean): Boolean {
        val token = b.etToken.text.toString().trim()
        val chat = b.etChatId.text.toString().trim()
        var interval = b.etInterval.text.toString().toIntOrNull() ?: Prefs.DEFAULT_INTERVAL
        if (interval < Prefs.MIN_INTERVAL) {
            interval = Prefs.MIN_INTERVAL
            toast("Минимальный интервал — ${Prefs.MIN_INTERVAL} с")
        }
        setField(b.etInterval, interval.toString())
        prefs.token = token
        prefs.chatId = chat
        prefs.intervalSec = interval

        if (!requireAll) return true
        val error = when {
            prefs.searches.none { it.enabled } -> "Добавьте хотя бы один включённый поиск"
            token.isBlank() -> "Укажите Telegram Bot Token"
            chat.isBlank() -> "Укажите Telegram Chat ID"
            else -> null
        }
        if (error != null) toast(error)
        return error == null
    }

    private fun updateBaseInfo() {
        val n = SeenStore.get(this).size()
        b.tvBaseInfo.text = "В базе $n из ${SeenStore.MAX_IDS} ID.\n" +
            "Первая проверка каждой новой ссылки — тихая: объявления запоминаются без отправки."
    }

    private fun updateSubtitle() {
        val list = prefs.searches.filter { it.enabled }
        b.tvSubtitle.text = when (list.size) {
            0 -> "Добавьте поиск в настройках"
            1 -> list[0].name
            else -> "${list.size} поиска: " + list.joinToString(", ") { it.name }
        }
    }

    private fun homeUrl(): String = prefs.searches.firstOrNull { it.enabled }?.url ?: "https://m.avito.ru"

    // ------------------------------------------------------------------ поиски

    /** Список поисков в настройках: тап — изменить, переключатель — вкл/выкл. */
    private fun renderSearchList() {
        val list = prefs.searches
        b.searchList.removeAllViews()
        list.forEach { s ->
            val row = ItemSearchBinding.inflate(layoutInflater, b.searchList, false)
            row.tvName.text = s.name
            row.tvLine1.text = s.url.removePrefix("https://").removePrefix("www.")
            row.tvLine2.text = "${s.priceText()} · ${s.wordsText()}"
            row.swEnabled.isChecked = s.enabled
            row.swEnabled.setOnCheckedChangeListener { _, c ->
                prefs.updateSearch(s.id) { it.copy(enabled = c) }
                if (c) ScannerHub.checkNow()
            }
            row.root.setOnClickListener { editSearch(s) }
            b.searchList.addView(row.root)
        }
        if (list.isEmpty()) {
            b.searchList.addView(
                android.widget.TextView(this).apply {
                    text = "Поисков пока нет — нажмите «Добавить поиск»."
                    setTextColor(ContextCompat.getColor(context, R.color.text_dim))
                    textSize = 13f
                    setPadding(0, dp(10), 0, 0)
                }
            )
        }
    }

    /** Поиски на вкладке «Обзор»: сколько на странице, медиана цены, отправлено. */
    private fun renderOverviewSearches() {
        val list = prefs.searches
        val stats = ScannerHub.searchStats.value
        b.overviewSearchesCard.isVisible = list.isNotEmpty()
        b.overviewSearches.removeAllViews()
        list.forEach { s ->
            val row = ItemSearchBinding.inflate(layoutInflater, b.overviewSearches, false)
            val st = stats[s.id]
            row.tvName.text = s.name
            row.tvName.alpha = if (s.enabled) 1f else 0.5f
            row.tvLine1.text = when {
                !s.enabled -> "выключен"
                st == null -> "ещё не проверялся · ${s.priceText()}"
                else -> "на странице ${st.found} · отправлено ${st.sent} · ${time(st.checkedAt)}"
            }
            val market = st?.let { MarketSummary.of(it.items, s) }
            row.tvLine2.isVisible = market != null
            if (market != null) row.tvLine2.text = "медиана ${money(market.median)} · обычно ${money(market.p25)}–${money(market.p75)}"
            row.swEnabled.isVisible = false
            row.root.setOnClickListener { editSearch(s) }
            b.overviewSearches.addView(row.root)
        }
    }

    private fun editSearch(existing: Search?) {
        val d = DialogSearchBinding.inflate(layoutInflater)
        existing?.let { s ->
            d.etUrl.setText(s.url)
            d.etName.setText(s.name)
            d.etPriceMin.setText(s.priceMin?.toString().orEmpty())
            d.etPriceMax.setText(s.priceMax?.toString().orEmpty())
            d.etStopWords.setText(s.stopWords.joinToString(", "))
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) "Новый поиск" else "Поиск «${existing.name}»")
            .setView(d.root)
            .setPositiveButton("Сохранить", null)
            .setNegativeButton("Отмена", null)
            .apply {
                if (existing != null) {
                    setNeutralButton("Удалить") { _, _ ->
                        prefs.updateSearch(existing.id) { null }
                        ScannerHub.searchStats.update { it - existing.id }
                        AppLog.i("Поиск «${existing.name}» удалён")
                    }
                }
            }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val url = Prefs.extractUrl(d.etUrl.text.toString())?.let(Prefs::normalizeUrl)
                    ?: Prefs.normalizeUrl(d.etUrl.text.toString())
                if (url.isBlank() || !Prefs.isAvito(url)) {
                    d.etUrl.error = "Нужна ссылка поиска avito.ru"
                    return@setOnClickListener
                }
                val search = Search(
                    id = existing?.id ?: Search.newId(),
                    name = d.etName.text.toString().trim().ifBlank { Search.guessName(url) }.take(40),
                    url = url,
                    priceMin = d.etPriceMin.text.toString().filter { it.isDigit() }.toLongOrNull()?.takeIf { it > 0 },
                    priceMax = d.etPriceMax.text.toString().filter { it.isDigit() }.toLongOrNull()?.takeIf { it > 0 },
                    stopWords = Search.parseWords(d.etStopWords.text.toString()),
                    enabled = existing?.enabled ?: true,
                )
                if (existing == null) prefs.addSearch(search) else prefs.updateSearch(existing.id) { search }
                if (existing == null || existing.url != search.url) {
                    ScannerHub.checkNow()
                    if (!ScannerHub.running.value) driver.loadManually(search.url)
                }
                AppLog.ok("Поиск «${search.name}» сохранён")
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun time(ms: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

    // ------------------------------------------------------------------ состояние / UI

    private data class UiState(
        val running: Boolean,
        val captcha: Boolean,
        val paused: Boolean,
        val status: String,
        val stats: Stats,
    )

    private fun renderState(s: UiState) {
        val (title, colorRes) = when {
            !s.running -> "Остановлен" to R.color.grey
            s.captcha -> "Нужна капча" to R.color.red
            s.paused -> "Пауза" to R.color.amber
            s.stats.busy -> "Проверяю…" to R.color.accent2
            else -> "Работает" to R.color.green
        }
        val color = ContextCompat.getColor(this, colorRes)
        b.tvHeroTitle.text = title
        b.tvStatusPill.text = title
        b.statusDot.backgroundTintList = ColorStateList.valueOf(color)
        b.tvHeroDetail.text = if (s.running) s.status else "Заполните настройки и нажмите «Старт»"
        b.busyProgress.visibility = if (s.running && s.stats.busy) View.VISIBLE else View.INVISIBLE

        b.btnStartStop.text = if (s.running) "Стоп" else "Старт"
        b.btnStartStop.setIconResource(if (s.running) R.drawable.ic_stop else R.drawable.ic_play)
        b.btnStartStop.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (s.running) R.color.red else R.color.accent)
        )
        b.btnPause.isEnabled = s.running
        b.btnPause.setIconResource(if (s.paused) R.drawable.ic_play else R.drawable.ic_pause)
        b.btnCheckNow.isEnabled = s.running && !s.paused && !s.captcha

        b.statCycles.text = s.stats.cycles.toString()
        b.statFound.text = s.stats.lastFound.toString()
        b.statSent.text = s.stats.sentTotal.toString()
        b.statBase.text = (if (s.running) s.stats.inBase else SeenStore.get(this).size()).toString()

        // Капча: будим экран и показываем браузер
        if (s.captcha && !lastCaptcha) {
            setNight(false)
            if (isWide == false) b.tabs.check(R.id.tabBrowser)
        }
        // Таймер ночного экрана перезапускаем только при смене режима, а не на каждое обновление статистики
        if (s.running != lastRunning || s.captcha != lastCaptcha) scheduleAutoNight()
        lastCaptcha = s.captcha
        lastRunning = s.running

        applyScreenMode()
        renderCountdown()
    }

    private fun renderCountdown() {
        val st = ScannerHub.stats.value
        val show = ScannerHub.running.value && !ScannerHub.paused.value && !ScannerHub.captcha.value && st.nextCheck > 0
        b.tvCountdown.isVisible = show
        if (show) {
            val sec = ((st.nextCheck - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
            b.tvCountdown.text = "Следующая проверка через %d:%02d".format(sec / 60, sec % 60)
        }
        if (nightOn) updateAod()
    }

    private fun renderLog(lines: List<String>) {
        if (lines.isEmpty()) {
            b.tvLog.text = "Здесь появится журнал работы"
            return
        }
        val dim = ContextCompat.getColor(this, R.color.text_dim)
        val faint = ContextCompat.getColor(this, R.color.text_faint)
        val green = ContextCompat.getColor(this, R.color.green)
        val red = ContextCompat.getColor(this, R.color.red)
        val amber = ContextCompat.getColor(this, R.color.amber)
        val sb = SpannableStringBuilder()
        // Новые записи сверху — видно без прокрутки
        for (line in lines.asReversed().take(150)) {
            val start = sb.length
            sb.append(line).append('\n')
            val color = when {
                line.contains(" ✓ ") -> green
                line.contains(" ✗ ") -> red
                line.contains(" ! ") -> amber
                else -> dim
            }
            sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(faint), start, minOf(start + 8, sb.length), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        b.tvLog.text = sb.trimEnd()
    }

    // ------------------------------------------------------------------ экран / ночной режим

    /**
     * Пока сканер работает, экран не гаснет (WebView рендерится корректно).
     * В ночном режиме яркость минимальная, на экране только тусклые часы и статус.
     */
    private fun applyScreenMode() {
        if (ScannerHub.running.value) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val attrs = window.attributes
        val target = if (nightOn) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        if (attrs.screenBrightness != target) {
            attrs.screenBrightness = target
            window.attributes = attrs
        }
    }

    private fun setNight(on: Boolean) {
        if (on == nightOn) return
        nightOn = on
        b.nightOverlay.isVisible = on
        val ctl = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            hideKeyboard()
            ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctl.hide(WindowInsetsCompat.Type.systemBars())
            aodJob?.cancel()
            aodJob = lifecycleScope.launch {
                var tick = 0
                while (isActive) {
                    updateAod()
                    if (tick % 60 == 0) moveAodBox() // сдвиг раз в минуту — защита OLED от выгорания
                    tick++
                    delay(1_000)
                }
            }
        } else {
            ctl.show(WindowInsetsCompat.Type.systemBars())
            aodJob?.cancel()
            scheduleAutoNight()
        }
        applyScreenMode()
    }

    private fun updateAod() {
        val st = ScannerHub.stats.value
        b.tvAodTime.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        b.tvAodStatus.text = b.tvHeroTitle.text
        val next = if (st.nextCheck > 0 && ScannerHub.running.value) {
            val sec = ((st.nextCheck - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
            " · след. через ${sec} с"
        } else ""
        b.tvAodStats.text = "проверок ${st.cycles} · отправлено ${st.sentTotal}$next"
    }

    private fun moveAodBox() {
        val box = b.aodBox
        val ow = b.nightOverlay.width
        val oh = b.nightOverlay.height
        if (ow == 0 || box.width == 0) {
            box.post { if (nightOn && b.nightOverlay.width > 0) moveAodBox() }
            return
        }
        box.translationX = Random.nextInt(max(1, ow - box.width)).toFloat()
        box.translationY = Random.nextInt(max(1, oh - box.height)).toFloat()
    }

    private fun scheduleAutoNight() {
        handler.removeCallbacks(autoNight)
        if (ScannerHub.running.value && prefs.autoNight && !nightOn && !ScannerHub.captcha.value) {
            handler.postDelayed(autoNight, AUTO_NIGHT_MS)
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun hideKeyboard() {
        currentFocus?.let {
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken, 0)
            it.clearFocus()
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val TAB_OVERVIEW = 0
        private const val TAB_BROWSER = 1
        private const val TAB_SETTINGS = 2
        private const val WIDE_DP = 600
        private const val AUTO_NIGHT_MS = 120_000L
    }
}
