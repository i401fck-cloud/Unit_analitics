package ru.avitomonitor

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * Foreground-сервис 24/7: держит WakeLock/WifiLock, крутит рандомизированный таймер
 * и слушает команды Telegram-бота. Сам WebView живёт в MainActivity — сервис управляет
 * им через [ScannerHub.driver].
 */
class AvitoParserService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null
    private var botJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private lateinit var prefs: Prefs
    private lateinit var seen: SeenStore

    private var emptyStreak = 0
    private var emptyAlertSent = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        seen = SeenStore.get(this)
        Notifier.createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground("Запуск…")

        if (intent?.action == ACTION_STOP) {
            prefs.serviceEnabled = false
            stopSelf()
            return START_NOT_STICKY
        }
        // intent == null — система перезапустила сервис после убийства процесса
        if (intent == null && !prefs.serviceEnabled) {
            stopSelf()
            return START_NOT_STICKY
        }

        prefs.serviceEnabled = true
        acquireLocks()
        ScannerHub.running.value = true
        if (loopJob?.isActive != true) {
            ScannerHub.stats.value = Stats(inBase = seen.size())
            loopJob = scope.launch { runLoop() }
        }
        if (botJob?.isActive != true) botJob = scope.launch { botLoop() }
        return START_STICKY
    }

    override fun onDestroy() {
        loopJob?.cancel()
        botJob?.cancel()
        scope.cancel()
        releaseLocks()
        Notifier.cancelCaptcha(this)
        ScannerHub.running.value = false
        ScannerHub.captcha.value = false
        ScannerHub.paused.value = false
        ScannerHub.stats.update { it.copy(busy = false, nextCheck = 0) }
        ScannerHub.status.value = "Остановлен"
        prefs.serviceEnabled = false
        AppLog.i("Сервис остановлен")
        super.onDestroy()
    }

    // ------------------------------------------------------------------ основной цикл

    private suspend fun runLoop() {
        AppLog.ok("Сервис запущен. В базе ${seen.size()} ID")
        while (currentCoroutineContext().isActive) {
            if (ScannerHub.paused.value) {
                ScannerHub.stats.update { it.copy(nextCheck = 0) }
                setStatus("⏸ Пауза — нажмите «Продолжить»")
                awaitWake(15_000)
                continue
            }
            val cfg = prefs.config()
            if (cfg.url.isBlank()) {
                setStatus("Не задан URL поиска")
                awaitWake(10_000)
                continue
            }
            val driver = ScannerHub.driver
            if (driver == null) {
                setStatus("Жду WebView — откройте приложение")
                delay(10_000)
                continue
            }

            val cycle = ScannerHub.stats.value.cycles + 1
            ScannerHub.stats.update { it.copy(cycles = cycle, busy = true, nextCheck = 0) }
            setStatus("Цикл #$cycle: загрузка страницы…")
            val result = try {
                driver.loadAndParse(cfg.url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ParseResult.Error(e.message ?: e.javaClass.simpleName)
            }

            when (result) {
                is ParseResult.Items -> process(cycle, result.items, cfg)
                is ParseResult.Captcha -> waitForCaptcha(cycle, cfg, result)
                is ParseResult.Error -> AppLog.e("Цикл #$cycle: ${result.message}")
            }

            val waitMs = cfg.intervalSec * 1000L + Random.nextLong(15_000, 40_001)
            val now = System.currentTimeMillis()
            ScannerHub.stats.update {
                it.copy(busy = false, lastCheck = now, nextCheck = now + waitMs, inBase = seen.size())
            }
            AppLog.i("Следующая проверка через ${waitMs / 1000} с")
            setStatus("Последняя проверка ${time(now)} · отправлено ${ScannerHub.stats.value.sentTotal}")
            if (awaitWake(waitMs) && !ScannerHub.paused.value) AppLog.i("Проверка по запросу")
        }
    }

    /** Ждёт [ms] или сигнала «проверить сейчас». true — если разбудили досрочно. */
    private suspend fun awaitWake(ms: Long): Boolean =
        withTimeoutOrNull(ms) { ScannerHub.wake.receive() } != null

    private suspend fun process(cycle: Int, items: List<Listing>, cfg: Config) {
        ScannerHub.stats.update { it.copy(lastFound = items.size) }
        if (items.isEmpty()) {
            emptyStreak++
            AppLog.w("Цикл #$cycle: 0 карточек (подряд: $emptyStreak)")
            if (emptyStreak >= 3 && !emptyAlertSent) {
                emptyAlertSent = true
                tg(cfg, "⚠️ 3 цикла подряд не найдено ни одной карточки. Проверьте браузер на телефоне.")
                sendScreen(cfg, "Так сейчас выглядит страница")
            }
            return
        }
        emptyStreak = 0
        emptyAlertSent = false

        // Первый проход по новому URL — молча заполняем базу
        if (prefs.seededUrl != cfg.url) {
            seen.addAll(items.map { it.id })
            prefs.seededUrl = cfg.url
            AppLog.ok("Первый проход: ${items.size} объявлений сохранено в базу без отправки")
            tg(
                cfg,
                "✅ <b>Мониторинг запущен</b>\nТекущих объявлений в базе: ${items.size}\n" +
                    "Дальше присылаю только новые.\n\n/help — команды управления",
                keyboard = statusKeyboard(),
            )
            return
        }

        seen.touchAll(items.map { it.id })
        val fresh = items.filter { !seen.contains(it.id) }
        var sent = 0
        var filtered = 0
        var failed = false

        // Страница отсортирована от новых к старым — отправляем в хронологическом порядке
        for (item in fresh.take(MAX_PER_CYCLE).reversed()) {
            val reason = cfg.rejectReason(item)
            if (reason != null) {
                seen.add(item.id)
                filtered++
                AppLog.i("Отсеяно ($reason): ${item.title.take(40)}")
                continue
            }
            val r = sendItem(cfg, item)
            if (r.isSuccess) {
                seen.add(item.id)
                sent++
                ScannerHub.stats.update { it.copy(sentTotal = it.sentTotal + 1) }
                AppLog.ok("→ ${item.title.take(50)} · ${item.price}")
            } else {
                AppLog.e("Telegram: ${r.exceptionOrNull()?.message}")
                failed = true
                break // не помечаем как увиденное — попробуем в следующем цикле
            }
            delay(1_100) // лимит Telegram ~1 сообщение/с в один чат
        }

        val msg = "Цикл #$cycle: на странице ${items.size}, новых ${fresh.size}, отправлено $sent, отсеяно $filtered"
        if (failed) AppLog.w(msg) else AppLog.ok(msg)
    }

    /** Скриншот (карточки или страницы) + описание + кнопка-ссылка; при неудаче — фото, затем текст. */
    private suspend fun sendItem(cfg: Config, item: Listing): Result<Unit> {
        val driver = ScannerHub.driver
        val shot = try {
            when (cfg.attach) {
                AttachMode.CARD -> driver?.screenshotCard(item.id)
                AttachMode.PAGE -> {
                    delay(Random.nextLong(1_500, 4_000)) // пауза перед переходом, как у человека
                    driver?.screenshotPage(item.url)
                }
                else -> null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (shot != null) {
            val kb = TelegramClient.keyboard(listOf(TelegramClient.urlButton("🔗 Открыть на Avito", item.url)))
            val r = TelegramClient.sendPhotoBytes(cfg.token, cfg.chatId, shot, TelegramClient.formatListing(item, 1024), kb)
            if (r.isSuccess) return r
            AppLog.w("Скриншот не отправился (${r.exceptionOrNull()?.message}), пробую фото/текст")
        } else if (cfg.attach == AttachMode.CARD || cfg.attach == AttachMode.PAGE) {
            AppLog.w("Скриншот не получился, отправляю фото/текст")
        }
        return TelegramClient.sendListing(cfg, item)
    }

    private suspend fun waitForCaptcha(cycle: Int, cfg: Config, first: ParseResult.Captcha) {
        ScannerHub.captcha.value = true
        ScannerHub.stats.update { it.copy(captchas = it.captchas + 1, busy = false) }
        AppLog.w("Цикл #$cycle: КАПЧА «${first.reason}». Таймер на паузе — пройдите проверку в браузере")
        setStatus("⚠️ Требуется ввод капчи!")
        Notifier.beep(this)
        Notifier.captcha(this)
        tg(cfg, "⚠️ <b>Требуется ввод капчи на телефоне!</b>\nМониторинг на паузе до прохождения проверки.")
        sendScreen(cfg, "Экран телефона сейчас")

        var lastBeep = System.currentTimeMillis()
        var cleanProbes = 0
        while (currentCoroutineContext().isActive) {
            delay(10_000)
            val driver = ScannerHub.driver ?: continue
            val r = try { driver.probe() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }

            if (r is ParseResult.Items && r.items.isNotEmpty()) {
                AppLog.ok("Капча пройдена, на странице ${r.items.size} карточек")
                process(cycle, r.items, cfg)
                break
            }
            // Пользователь ушёл со страницы капчи (например, на логин) — 3 «чистые» проверки подряд
            cleanProbes = if (r is ParseResult.Items) cleanProbes + 1 else 0
            if (cleanProbes >= 3) {
                AppLog.ok("Признаков капчи больше нет — продолжаю")
                break
            }
            if (System.currentTimeMillis() - lastBeep > 120_000) {
                Notifier.beep(this)
                lastBeep = System.currentTimeMillis()
            }
        }
        ScannerHub.captcha.value = false
        Notifier.cancelCaptcha(this)
        tg(cfg, "✅ Капча пройдена, мониторинг продолжается.")
    }

    // ------------------------------------------------------------------ Telegram-бот

    private suspend fun botLoop() {
        var offset: Long? = null
        var lastToken = ""
        while (currentCoroutineContext().isActive) {
            val cfg = prefs.config()
            if (cfg.token.isBlank() || cfg.chatId.isBlank()) {
                delay(30_000)
                continue
            }
            if (offset == null || cfg.token != lastToken) {
                // Пропускаем старые сообщения, чтобы не выполнить давние команды
                val last = TelegramClient.getUpdates(cfg.token, -1, 0)
                if (last.isFailure) {
                    delay(30_000)
                    continue
                }
                offset = (last.getOrNull()?.lastOrNull()?.updateId ?: -1L) + 1
                lastToken = cfg.token
                TelegramClient.setCommands(cfg.token)
                continue
            }
            val res = TelegramClient.getUpdates(cfg.token, offset, 50)
            val updates = res.getOrNull()
            if (updates == null) {
                delay(15_000)
                continue
            }
            for (u in updates) {
                offset = u.updateId + 1
                if (u.chatId != cfg.chatId) continue // команды только из своего чата
                u.callbackId?.let { TelegramClient.answerCallback(cfg.token, it) }
                if (u.text.startsWith("/")) {
                    try {
                        handleCommand(u.text.trim(), cfg)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLog.e("Команда ${u.text}: ${e.message}")
                    }
                }
            }
        }
    }

    private suspend fun handleCommand(text: String, cfg: Config) {
        val cmd = text.substringBefore(' ').substringBefore('@').lowercase()
        val arg = text.substringAfter(' ', "").trim()
        AppLog.i("Telegram-команда: $cmd")
        when (cmd) {
            "/start", "/help" -> tg(cfg, HELP)
            "/status" -> tg(cfg, statusText(cfg), keyboard = statusKeyboard())
            "/pause" -> {
                ScannerHub.paused.value = true
                ScannerHub.checkNow()
                tg(cfg, "⏸ Мониторинг на паузе.", keyboard = statusKeyboard())
            }
            "/resume" -> {
                ScannerHub.paused.value = false
                ScannerHub.checkNow()
                tg(cfg, "▶️ Продолжаю, проверяю сейчас.", keyboard = statusKeyboard())
            }
            "/check" -> {
                if (ScannerHub.paused.value) {
                    tg(cfg, "Сейчас пауза. Нажмите «Продолжить».", keyboard = statusKeyboard())
                } else {
                    ScannerHub.checkNow()
                    tg(cfg, "🔄 Проверяю прямо сейчас…")
                }
            }
            "/screen" -> if (!sendScreen(cfg, "Браузер на телефоне")) tg(cfg, "Не удалось сделать скриншот (приложение свернуто?)")
            "/filters" -> tg(cfg, "🎛 <b>Фильтры</b>\n" + TelegramClient.esc(cfg.describeFilters()))
            "/price" -> {
                val nums = Regex("\\d+").findAll(arg.replace(" ", "")).map { it.value }.toList()
                when {
                    arg.isBlank() -> tg(cfg, "Пример: <code>/price 1000 50000</code>, <code>/price 0 30000</code>, <code>/price off</code>")
                    arg.equals("off", true) || arg == "-" -> { prefs.priceMin = ""; prefs.priceMax = "" }
                    nums.size >= 2 -> { prefs.priceMin = nums[0].trimStart('0'); prefs.priceMax = nums[1] }
                    else -> { prefs.priceMin = ""; prefs.priceMax = nums.firstOrNull().orEmpty() }
                }
                if (arg.isNotBlank()) tg(cfg, "✅ " + TelegramClient.esc(prefs.config().describeFilters()))
            }
            "/words" -> {
                if (arg.isBlank()) {
                    tg(cfg, "Пример: <code>/words битый, ремонт, запчасти</code> или <code>/words off</code>")
                } else {
                    prefs.stopWords = if (arg.equals("off", true)) "" else arg
                    tg(cfg, "✅ " + TelegramClient.esc(prefs.config().describeFilters()))
                }
            }
            "/interval" -> {
                val n = arg.toIntOrNull()
                if (n == null) {
                    tg(cfg, "Пример: <code>/interval 60</code> (минимум ${Prefs.MIN_INTERVAL})")
                } else {
                    prefs.intervalSec = n.coerceAtLeast(Prefs.MIN_INTERVAL)
                    tg(cfg, "✅ Интервал: ${prefs.intervalSec} с (+15–40 с случайно)")
                }
            }
            "/url" -> {
                val url = Prefs.normalizeUrl(arg)
                if (!android.net.Uri.parse(url).host.orEmpty().endsWith("avito.ru")) {
                    tg(cfg, "Пришлите ссылку поиска Avito: <code>/url https://www.avito.ru/...</code>")
                } else {
                    prefs.url = url
                    ScannerHub.checkNow()
                    tg(cfg, "✅ Новая ссылка сохранена. Первый проход по ней будет тихим.")
                }
            }
            "/attach" -> {
                val mode = AttachMode.entries.firstOrNull { it.key == arg.lowercase() }
                if (mode == null) {
                    tg(
                        cfg,
                        "Что прикладывать к объявлениям:\n" +
                            "<code>/attach card</code> — скриншот карточки (быстро)\n" +
                            "<code>/attach page</code> — скриншот страницы объявления (медленнее)\n" +
                            "<code>/attach photo</code> — фото из объявления\n" +
                            "<code>/attach none</code> — только текст\n\nСейчас: ${cfg.attach.title}",
                    )
                } else {
                    prefs.attachMode = mode
                    tg(cfg, "✅ Вложение: ${mode.title}")
                }
            }
            else -> tg(cfg, "Не знаю такую команду. /help — список команд.")
        }
    }

    private fun statusText(cfg: Config): String {
        val s = ScannerHub.stats.value
        val state = when {
            ScannerHub.captcha.value -> "⚠️ ждёт капчу"
            ScannerHub.paused.value -> "⏸ пауза"
            s.busy -> "🔄 проверяет"
            else -> "▶️ работает"
        }
        val next = if (s.nextCheck > 0) {
            val sec = ((s.nextCheck - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
            "через $sec с"
        } else "—"
        return buildString {
            append("📊 <b>Avito Monitor</b> — ").append(state).append('\n')
            append("Циклов: <b>").append(s.cycles).append("</b> · последний: ")
                .append(if (s.lastCheck > 0) time(s.lastCheck) else "—").append('\n')
            append("Следующая проверка: ").append(next).append('\n')
            append("На странице: <b>").append(s.lastFound).append("</b> · в базе: ").append(seen.size()).append('\n')
            append("Отправлено за сессию: <b>").append(s.sentTotal).append("</b> · капч: ").append(s.captchas).append("\n\n")
            append(TelegramClient.esc(cfg.describeFilters())).append('\n')
            append("🔎 <a href=\"").append(TelegramClient.esc(cfg.url)).append("\">Открыть поиск</a>")
        }
    }

    private fun statusKeyboard(): String {
        val pauseBtn = if (ScannerHub.paused.value) {
            TelegramClient.cbButton("▶️ Продолжить", "/resume")
        } else {
            TelegramClient.cbButton("⏸ Пауза", "/pause")
        }
        return TelegramClient.keyboard(
            listOf(pauseBtn, TelegramClient.cbButton("🔄 Проверить", "/check")),
            listOf(TelegramClient.cbButton("📸 Скрин", "/screen"), TelegramClient.cbButton("📊 Статус", "/status")),
        )
    }

    private suspend fun sendScreen(cfg: Config, caption: String): Boolean {
        val jpeg = ScannerHub.driver?.screenshot() ?: return false
        val r = TelegramClient.sendPhotoBytes(cfg.token, cfg.chatId, jpeg, "📸 " + TelegramClient.esc(caption))
        r.onFailure { AppLog.e("Скриншот: ${it.message}") }
        return r.isSuccess
    }

    // ------------------------------------------------------------------ helpers

    private suspend fun tg(cfg: Config, html: String, keyboard: String? = null) {
        TelegramClient.send(cfg.token, cfg.chatId, html, preview = false, keyboard = keyboard)
            .onFailure { AppLog.e("Telegram: ${it.message}") }
    }

    private fun setStatus(text: String) {
        ScannerHub.status.value = text
        Notifier.update(this, text)
    }

    private fun time(ms: Long) = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ms))

    private fun startAsForeground(text: String) {
        val n = Notifier.service(this, text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(Notifier.ID_SERVICE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(Notifier.ID_SERVICE, n)
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        if (wakeLock?.isHeld != true) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AvitoMonitor::scanner")
                .apply { setReferenceCounted(false); acquire() }
        }
        if (wifiLock?.isHeld != true) {
            wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AvitoMonitor::wifi")
                .apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun releaseLocks() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock = null
    }

    companion object {
        const val ACTION_STOP = "ru.avitomonitor.STOP"
        private const val MAX_PER_CYCLE = 20

        private val HELP = """
            🤖 <b>Avito Monitor — команды</b>

            /status — состояние, статистика, кнопки
            /check — проверить прямо сейчас
            /pause, /resume — пауза / продолжить
            /screen — скриншот браузера на телефоне
            /filters — текущие фильтры
            /price 1000 50000 — вилка цен (<code>/price off</code> — сброс)
            /words битый, ремонт — стоп-слова (<code>/words off</code>)
            /interval 60 — интервал, сек
            /url ссылка — новая ссылка поиска
            /attach card|page|photo|none — что прикладывать к объявлениям

            Бот отвечает только в этом чате и только пока на телефоне нажат «Старт».
        """.trimIndent()
    }
}
