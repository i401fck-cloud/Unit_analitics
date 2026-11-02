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
 * Foreground-сервис 24/7: держит WakeLock/WifiLock, по очереди проверяет все включённые поиски
 * и обслуживает Telegram-бота (кнопки под полем ввода + инлайн-кнопки).
 * Сам WebView живёт в MainActivity — сервис управляет им через [ScannerHub.driver].
 */
class AvitoParserService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null
    private var botJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private lateinit var prefs: Prefs
    private lateinit var seen: SeenStore

    private val emptyStreak = HashMap<String, Int>()

    /** Бот ждёт от пользователя значение (цена, стоп-слова, ссылка…). */
    private sealed interface Pending {
        data object AddSearch : Pending
        data object Interval : Pending
        data class Price(val id: String) : Pending
        data class Words(val id: String) : Pending
        data class Name(val id: String) : Pending
    }

    @Volatile private var pending: Pending? = null

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
        var greeted = false
        while (currentCoroutineContext().isActive) {
            if (ScannerHub.paused.value) {
                ScannerHub.stats.update { it.copy(nextCheck = 0) }
                setStatus("⏸ Пауза — нажмите «Продолжить»")
                awaitWake(15_000)
                continue
            }
            val cfg = prefs.config()
            val searches = cfg.active
            if (searches.isEmpty()) {
                setStatus("Добавьте поиск в настройках или кнопкой в Telegram")
                awaitWake(10_000)
                continue
            }
            if (!greeted) {
                greeted = true
                tg(cfg, "▶️ Мониторинг запущен: поисков ${searches.size}.", keyboard = TelegramClient.replyKeyboard())
            }
            val driver = ScannerHub.driver
            if (driver == null) {
                setStatus("Жду WebView — откройте приложение")
                delay(10_000)
                continue
            }

            val cycle = ScannerHub.stats.value.cycles + 1
            ScannerHub.stats.update { it.copy(cycles = cycle, busy = true, nextCheck = 0) }
            var found = 0
            for ((i, search) in searches.withIndex()) {
                if (ScannerHub.paused.value || !currentCoroutineContext().isActive) break
                if (i > 0) delay(Random.nextLong(3_000, 8_001)) // пауза между поисками, как у человека
                val label = if (searches.size > 1) "«${search.name}» (${i + 1}/${searches.size})" else "«${search.name}»"
                setStatus("Цикл #$cycle: $label…")
                val result = try {
                    driver.loadAndParse(search.url)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ParseResult.Error(e.message ?: e.javaClass.simpleName)
                }
                when (result) {
                    is ParseResult.Items -> {
                        found += result.items.size
                        process(cycle, search, result.items, cfg)
                    }
                    is ParseResult.Captcha -> waitForCaptcha(cycle, search, cfg, result)
                    is ParseResult.Error -> AppLog.e("#$cycle ${search.name}: ${result.message}")
                }
            }

            val waitMs = cfg.intervalSec * 1000L + Random.nextLong(4_000, 13_001)
            val now = System.currentTimeMillis()
            ScannerHub.stats.update {
                it.copy(busy = false, lastFound = found, lastCheck = now, nextCheck = now + waitMs, inBase = seen.size())
            }
            AppLog.i("Следующая проверка через ${waitMs / 1000} с")
            setStatus("Последняя проверка ${time(now)} · отправлено ${ScannerHub.stats.value.sentTotal}")
            if (awaitWake(waitMs) && !ScannerHub.paused.value) AppLog.i("Проверка по запросу")
        }
    }

    /** Ждёт [ms] или сигнала «проверить сейчас». true — если разбудили досрочно. */
    private suspend fun awaitWake(ms: Long): Boolean =
        withTimeoutOrNull(ms) { ScannerHub.wake.receive() } != null

    private fun updateSearchStat(id: String, change: (SearchStat) -> SearchStat) {
        ScannerHub.searchStats.update { it + (id to change(it[id] ?: SearchStat())) }
    }

    private suspend fun process(cycle: Int, search: Search, items: List<Listing>, cfg: Config) {
        updateSearchStat(search.id) { it.copy(found = items.size, items = items, checkedAt = System.currentTimeMillis()) }
        if (items.isEmpty()) {
            val streak = (emptyStreak[search.id] ?: 0) + 1
            emptyStreak[search.id] = streak
            AppLog.w("#$cycle ${search.name}: 0 карточек (подряд: $streak)")
            if (streak == 3) {
                tg(cfg, "⚠️ «${esc(search.name)}»: 3 цикла подряд не найдено ни одной карточки. Проверьте браузер на телефоне.")
                sendScreen(cfg, "Так сейчас выглядит страница")
            }
            return
        }
        emptyStreak[search.id] = 0

        // Первый проход по новой ссылке — молча заполняем базу и присылаем рыночную цену
        if (!prefs.isSeeded(search.url)) {
            seen.addAll(items.map { it.id })
            prefs.markSeeded(search.url)
            AppLog.ok("${search.name}: первый проход, ${items.size} объявлений в базу без отправки")
            val market = MarketSummary.of(items, search)
            tg(
                cfg,
                "✅ <b>${esc(search.name)}</b>: в базе ${items.size} текущих объявлений, дальше пришлю только новые." +
                    (market?.let { "\nМедиана цены сейчас: <b>${money(it.median)}</b>" } ?: ""),
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
            val reason = search.rejectReason(item)
            if (reason != null) {
                seen.add(item.id)
                filtered++
                AppLog.i("Отсеяно ($reason): ${item.title.take(40)}")
                continue
            }
            val r = sendItem(cfg, search, item, multi = cfg.active.size > 1)
            if (r.isSuccess) {
                seen.add(item.id)
                sent++
                ScannerHub.stats.update { it.copy(sentTotal = it.sentTotal + 1) }
                updateSearchStat(search.id) { it.copy(sent = it.sent + 1) }
                AppLog.ok("→ ${item.title.take(50)} · ${item.price}")
            } else {
                AppLog.e("Telegram: ${r.exceptionOrNull()?.message}")
                failed = true
                break // не помечаем как увиденное — попробуем в следующем цикле
            }
            delay(1_100) // лимит Telegram ~1 сообщение/с в один чат
        }

        val msg = "#$cycle ${search.name}: на странице ${items.size}, новых ${fresh.size}, отправлено $sent, отсеяно $filtered"
        if (failed) AppLog.w(msg) else AppLog.ok(msg)
    }

    /** Скриншот (карточки или страницы) + описание + кнопка-ссылка; при неудаче — фото, затем текст. */
    private suspend fun sendItem(cfg: Config, search: Search, item: Listing, multi: Boolean): Result<Unit> {
        val tag = if (multi) search.name else null
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
            val r = TelegramClient.sendPhotoBytes(cfg.token, cfg.chatId, shot, TelegramClient.formatListing(item, 1024, tag), kb)
            if (r.isSuccess) return r
            AppLog.w("Скриншот не отправился (${r.exceptionOrNull()?.message}), пробую фото/текст")
        } else if (cfg.attach == AttachMode.CARD || cfg.attach == AttachMode.PAGE) {
            AppLog.w("Скриншот не получился, отправляю фото/текст")
        }
        return TelegramClient.sendListing(cfg, item, tag)
    }

    private suspend fun waitForCaptcha(cycle: Int, search: Search, cfg: Config, first: ParseResult.Captcha) {
        ScannerHub.captcha.value = true
        ScannerHub.stats.update { it.copy(captchas = it.captchas + 1, busy = false) }
        AppLog.w("#$cycle: КАПЧА «${first.reason}». Таймер на паузе — пройдите проверку в браузере")
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
                process(cycle, search, r.items, cfg)
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
                // Пропускаем старые сообщения, чтобы не выполнить давние нажатия
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
            val updates = TelegramClient.getUpdates(cfg.token, offset, 50).getOrNull()
            if (updates == null) {
                delay(15_000)
                continue
            }
            for (u in updates) {
                offset = u.updateId + 1
                if (u.chatId != cfg.chatId) continue // управление только из своего чата
                u.callbackId?.let { TelegramClient.answerCallback(cfg.token, it) }
                try {
                    if (u.callbackId != null) onCallback(u.text, prefs.config()) else onText(u.text.trim(), prefs.config())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.e("Telegram «${u.text.take(30)}»: ${e.message}")
                }
            }
        }
    }

    /** Текст: кнопка нижней клавиатуры, команда или ответ на вопрос бота. */
    private suspend fun onText(text: String, cfg: Config) {
        when (text.substringBefore(' ').substringBefore('@').lowercase()) {
            "/start", "/help", "/menu" -> {
                pending = null
                tg(cfg, HELP, keyboard = TelegramClient.replyKeyboard())
                return
            }
            "/status" -> return showStatus(cfg)
        }
        when (text) {
            TelegramClient.BTN_STATUS -> { pending = null; showStatus(cfg) }
            TelegramClient.BTN_CHECK -> { pending = null; checkNow(cfg) }
            TelegramClient.BTN_PAUSE -> { pending = null; setPaused(cfg, true) }
            TelegramClient.BTN_RESUME -> { pending = null; setPaused(cfg, false) }
            TelegramClient.BTN_MARKET -> { pending = null; showMarket(cfg, null) }
            TelegramClient.BTN_SCREEN -> {
                pending = null
                if (!sendScreen(cfg, "Браузер на телефоне")) tg(cfg, "Не удалось сделать скриншот.")
            }
            TelegramClient.BTN_SEARCHES -> { pending = null; showSearches(cfg) }
            TelegramClient.BTN_SETTINGS -> { pending = null; showSettings(cfg) }
            else -> {
                val p = pending
                if (p != null) onInput(p, text, cfg)
                else tg(cfg, "Пользуйтесь кнопками внизу 👇", keyboard = TelegramClient.replyKeyboard())
            }
        }
    }

    /** Нажатия инлайн-кнопок под сообщениями. */
    private suspend fun onCallback(data: String, cfg: Config) {
        val parts = data.split(':')
        val id = parts.getOrNull(2).orEmpty()
        when (parts[0]) {
            "st" -> showStatus(cfg)
            "chk" -> checkNow(cfg)
            "pz" -> setPaused(cfg, true)
            "rs" -> setPaused(cfg, false)
            "scr" -> if (!sendScreen(cfg, "Браузер на телефоне")) tg(cfg, "Не удалось сделать скриншот.")
            "mk" -> showMarket(cfg, parts.getOrNull(1))
            "sl" -> showSearches(cfg)
            "set" -> showSettings(cfg)
            "x" -> { pending = null; tg(cfg, "Отменено.") }
            "iv" -> {
                pending = Pending.Interval
                tg(cfg, "⏱ Пришлите интервал в секундах (минимум ${Prefs.MIN_INTERVAL}). Сейчас: ${cfg.intervalSec} с.", keyboard = cancelKb())
            }
            "at" -> {
                val mode = parts.getOrNull(1)?.let { k -> AttachMode.entries.firstOrNull { it.key == k } }
                if (mode == null) {
                    tg(
                        cfg, "🖼 Что прикладывать к объявлениям? Сейчас: <b>${cfg.attach.title}</b>",
                        keyboard = TelegramClient.keyboard(
                            listOf(TelegramClient.cbButton("Скрин карточки", "at:card"), TelegramClient.cbButton("Скрин страницы", "at:page")),
                            listOf(TelegramClient.cbButton("Фото", "at:photo"), TelegramClient.cbButton("Только текст", "at:none")),
                        ),
                    )
                } else {
                    prefs.attachMode = mode
                    tg(cfg, "✅ Вложение: ${mode.title}")
                }
            }
            "s" -> onSearchAction(parts.getOrNull(1).orEmpty(), id, cfg)
        }
    }

    private suspend fun onSearchAction(action: String, id: String, cfg: Config) {
        if (action == "a") {
            pending = Pending.AddSearch
            tg(
                cfg,
                "➕ Пришлите ссылку поиска Avito.\n\nКак получить: настройте поиск на Avito (город, категория, фильтры) → «Поделиться» → «Копировать ссылку».",
                keyboard = cancelKb(),
            )
            return
        }
        val s = prefs.search(id) ?: return tg(cfg, "Поиск не найден — возможно, он уже удалён.")
        when (action) {
            "t" -> {
                prefs.updateSearch(id) { it.copy(enabled = !it.enabled) }
                if (!s.enabled) ScannerHub.checkNow()
                showSearches(prefs.config())
            }
            "e" -> tg(
                cfg,
                searchCard(s),
                keyboard = TelegramClient.keyboard(
                    listOf(TelegramClient.cbButton("💵 Цена", "s:p:$id"), TelegramClient.cbButton("🚫 Стоп-слова", "s:w:$id")),
                    listOf(TelegramClient.cbButton("✏️ Название", "s:n:$id"), TelegramClient.cbButton("💰 Цены рынка", "mk:$id")),
                    listOf(TelegramClient.cbButton("🗑 Удалить", "s:d:$id"), TelegramClient.cbButton("« Все поиски", "sl")),
                ),
            )
            "p" -> {
                pending = Pending.Price(id)
                tg(
                    cfg,
                    "💵 «${esc(s.name)}»: пришлите вилку цен двумя числами, например <code>10000 50000</code>.\n" +
                        "Только максимум: <code>0 50000</code>. Без ограничений: <code>0</code>.\nСейчас: ${s.priceText()}",
                    keyboard = cancelKb(),
                )
            }
            "w" -> {
                pending = Pending.Words(id)
                tg(
                    cfg,
                    "🚫 «${esc(s.name)}»: пришлите стоп-слова через запятую, например <code>битый, ремонт, запчасти</code>.\n" +
                        "Убрать все: <code>-</code>\nСейчас: ${esc(s.wordsText())}",
                    keyboard = cancelKb(),
                )
            }
            "n" -> {
                pending = Pending.Name(id)
                tg(cfg, "✏️ Пришлите новое название для «${esc(s.name)}».", keyboard = cancelKb())
            }
            "d" -> tg(
                cfg, "🗑 Удалить поиск «${esc(s.name)}»?",
                keyboard = TelegramClient.keyboard(
                    listOf(TelegramClient.cbButton("Да, удалить", "s:D:$id"), TelegramClient.cbButton("Нет", "sl")),
                ),
            )
            "D" -> {
                prefs.updateSearch(id) { null }
                ScannerHub.searchStats.update { it - id }
                tg(cfg, "🗑 Поиск «${esc(s.name)}» удалён.")
                showSearches(prefs.config())
            }
        }
    }

    private suspend fun onInput(p: Pending, text: String, cfg: Config) {
        when (p) {
            Pending.AddSearch -> {
                val url = Prefs.extractUrl(text)?.let(Prefs::normalizeUrl)
                if (url == null || !Prefs.isAvito(url)) {
                    tg(cfg, "Это не похоже на ссылку Avito. Пришлите ссылку вида https://www.avito.ru/...", keyboard = cancelKb())
                    return
                }
                val s = Search(id = Search.newId(), name = Search.guessName(url), url = url)
                prefs.addSearch(s)
                pending = null
                ScannerHub.checkNow()
                tg(
                    cfg,
                    "✅ Поиск «${esc(s.name)}» добавлен. Первая проверка будет тихой — потом пришлю новые объявления.",
                    keyboard = TelegramClient.keyboard(
                        listOf(TelegramClient.cbButton("💵 Цена", "s:p:${s.id}"), TelegramClient.cbButton("🚫 Стоп-слова", "s:w:${s.id}")),
                        listOf(TelegramClient.cbButton("✏️ Название", "s:n:${s.id}")),
                    ),
                )
            }
            Pending.Interval -> {
                val n = text.filter { it.isDigit() }.toIntOrNull()
                if (n == null) return tg(cfg, "Пришлите число секунд, например 60.", keyboard = cancelKb())
                prefs.intervalSec = n.coerceAtLeast(Prefs.MIN_INTERVAL)
                pending = null
                tg(cfg, "✅ Интервал: ${prefs.intervalSec} с (+4–13 с случайно)")
            }
            is Pending.Price -> {
                val nums = Regex("\\d[\\d  ]*").findAll(text).mapNotNull { it.value.filter(Char::isDigit).take(12).toLongOrNull() }.toList()
                if (nums.isEmpty()) return tg(cfg, "Пришлите одно или два числа, например <code>10000 50000</code>.", keyboard = cancelKb())
                val min = nums[0].takeIf { it > 0 }
                val max = nums.getOrNull(1)?.takeIf { it > 0 }
                prefs.updateSearch(p.id) { it.copy(priceMin = min, priceMax = max) }
                pending = null
                tg(cfg, "✅ «${esc(prefs.search(p.id)?.name.orEmpty())}»: ${prefs.search(p.id)?.priceText()}")
            }
            is Pending.Words -> {
                val words = if (text.trim() == "-") emptyList() else Search.parseWords(text)
                prefs.updateSearch(p.id) { it.copy(stopWords = words) }
                pending = null
                tg(cfg, "✅ Стоп-слова: ${esc(prefs.search(p.id)?.wordsText().orEmpty())}")
            }
            is Pending.Name -> {
                val name = text.trim().take(40)
                if (name.isBlank()) return
                prefs.updateSearch(p.id) { it.copy(name = name) }
                pending = null
                tg(cfg, "✅ Название: ${esc(name)}")
            }
        }
    }

    // ------------------------------------------------------------------ экраны бота

    private suspend fun showStatus(cfg: Config) {
        val s = ScannerHub.stats.value
        val state = when {
            ScannerHub.captcha.value -> "⚠️ ждёт капчу"
            ScannerHub.paused.value -> "⏸ пауза"
            s.busy -> "🔄 проверяет"
            else -> "▶️ работает"
        }
        val next = if (s.nextCheck > 0) "через ${((s.nextCheck - System.currentTimeMillis()) / 1000).coerceAtLeast(0)} с" else "—"
        val text = buildString {
            append("📊 <b>Avito Monitor</b> — ").append(state).append('\n')
            append("Проверок: <b>").append(s.cycles).append("</b> · последняя: ")
                .append(if (s.lastCheck > 0) time(s.lastCheck) else "—").append('\n')
            append("Следующая: ").append(next).append(" · интервал ").append(cfg.intervalSec).append(" с\n")
            append("Отправлено за сессию: <b>").append(s.sentTotal).append("</b> · в базе: ").append(seen.size())
                .append(" · капч: ").append(s.captchas).append("\n\n")
            val stats = ScannerHub.searchStats.value
            cfg.searches.forEach { search ->
                val st = stats[search.id]
                append(if (search.enabled) "▶️ " else "⏸ ").append("<b>").append(esc(search.name)).append("</b>")
                if (st != null) append(" — на странице ").append(st.found).append(", отправлено ").append(st.sent)
                append('\n')
            }
        }
        tg(cfg, text, keyboard = statusKeyboard())
    }

    private suspend fun showMarket(cfg: Config, onlyId: String?) {
        val stats = ScannerHub.searchStats.value
        val list = cfg.searches.filter { onlyId == null || it.id == onlyId }
        if (list.isEmpty()) return tg(cfg, "Нет поисков. Добавьте: 🔎 Поиски → ➕")
        val blocks = list.map { s ->
            val st = stats[s.id]
            if (st == null || st.items.isEmpty()) {
                "💰 <b>${esc(s.name)}</b>\nЕщё нет данных — дождитесь проверки."
            } else {
                TelegramClient.formatMarket(s, MarketSummary.of(st.items, s), st.found)
            }
        }
        tg(cfg, blocks.joinToString("\n\n"))
    }

    private suspend fun showSearches(cfg: Config) {
        if (cfg.searches.isEmpty()) {
            return tg(
                cfg, "🔎 Поисков пока нет.",
                keyboard = TelegramClient.keyboard(listOf(TelegramClient.cbButton("➕ Добавить поиск", "s:a"))),
            )
        }
        val text = buildString {
            append("🔎 <b>Поиски</b> (").append(cfg.searches.size).append(")\n\n")
            cfg.searches.forEachIndexed { i, s ->
                append(i + 1).append(". ").append(if (s.enabled) "▶️ " else "⏸ ").append("<b>").append(esc(s.name)).append("</b>\n")
                append("    ").append(s.priceText()).append(" · ").append(esc(s.wordsText())).append('\n')
            }
            append("\nНажмите на поиск, чтобы изменить. ⏯ — включить/выключить.")
        }
        val rows = cfg.searches.map { s ->
            listOf(
                TelegramClient.cbButton("${if (s.enabled) "▶️" else "⏸"} ${s.name}".take(40), "s:e:${s.id}"),
                TelegramClient.cbButton("⏯", "s:t:${s.id}"),
                TelegramClient.cbButton("💰", "mk:${s.id}"),
            )
        } + listOf(listOf(TelegramClient.cbButton("➕ Добавить поиск", "s:a")))
        tg(cfg, text, keyboard = TelegramClient.keyboard(*rows.toTypedArray()))
    }

    private suspend fun showSettings(cfg: Config) {
        tg(
            cfg,
            "⚙️ <b>Настройки</b>\nИнтервал: ${cfg.intervalSec} с (+4–13 с случайно)\nВложение: ${cfg.attach.title}",
            keyboard = TelegramClient.keyboard(
                listOf(TelegramClient.cbButton("⏱ Интервал", "iv"), TelegramClient.cbButton("🖼 Вложение", "at")),
                listOf(TelegramClient.cbButton("🔎 Поиски", "sl")),
            ),
        )
    }

    private suspend fun checkNow(cfg: Config) {
        if (ScannerHub.paused.value) {
            tg(cfg, "Сейчас пауза. Нажмите «▶️ Продолжить».")
        } else {
            ScannerHub.checkNow()
            tg(cfg, "🔄 Проверяю прямо сейчас…")
        }
    }

    private suspend fun setPaused(cfg: Config, pause: Boolean) {
        ScannerHub.paused.value = pause
        ScannerHub.checkNow()
        tg(cfg, if (pause) "⏸ Мониторинг на паузе." else "▶️ Продолжаю, проверяю сейчас.")
    }

    private fun searchCard(s: Search) = buildString {
        append(if (s.enabled) "▶️ " else "⏸ ").append("<b>").append(esc(s.name)).append("</b>\n")
        append("💵 ").append(s.priceText()).append('\n')
        append("🚫 ").append(esc(s.wordsText())).append('\n')
        append("🔗 <a href=\"").append(esc(s.url)).append("\">Открыть поиск</a>")
    }

    private fun statusKeyboard(): String = TelegramClient.keyboard(
        listOf(
            if (ScannerHub.paused.value) TelegramClient.cbButton("▶️ Продолжить", "rs") else TelegramClient.cbButton("⏸ Пауза", "pz"),
            TelegramClient.cbButton("🔄 Проверить", "chk"),
        ),
        listOf(TelegramClient.cbButton("💰 Цены рынка", "mk"), TelegramClient.cbButton("📸 Скрин", "scr")),
    )

    private fun cancelKb() = TelegramClient.keyboard(listOf(TelegramClient.cbButton("✖️ Отмена", "x")))

    private suspend fun sendScreen(cfg: Config, caption: String): Boolean {
        val jpeg = ScannerHub.driver?.screenshot() ?: return false
        val r = TelegramClient.sendPhotoBytes(cfg.token, cfg.chatId, jpeg, "📸 " + esc(caption))
        r.onFailure { AppLog.e("Скриншот: ${it.message}") }
        return r.isSuccess
    }

    // ------------------------------------------------------------------ helpers

    private fun esc(s: String) = TelegramClient.esc(s)

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
            🤖 <b>Avito Monitor</b>

            Управление — кнопками внизу 👇
            📊 Статус — состояние и статистика
            🔄 Проверить — проверить прямо сейчас
            ⏸ Пауза / ▶️ Продолжить
            💰 Цены рынка — медиана и диапазон цен по каждому поиску
            📸 Скрин — что сейчас на экране телефона
            🔎 Поиски — список, добавить, цена, стоп-слова, удалить
            ⚙️ Настройки — интервал и что прикладывать к объявлениям

            Бот отвечает только в этом чате и только пока на телефоне нажат «Старт».
        """.trimIndent()
    }
}
