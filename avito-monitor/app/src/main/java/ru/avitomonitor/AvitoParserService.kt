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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Foreground-сервис 24/7: держит WakeLock/WifiLock и крутит рандомизированный таймер.
 * Сам WebView живёт в MainActivity — сервис управляет им через [ScannerHub.driver].
 */
class AvitoParserService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private lateinit var prefs: Prefs
    private lateinit var seen: SeenStore

    private var cycle = 0
    private var totalSent = 0
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
        if (loopJob?.isActive != true) loopJob = scope.launch { runLoop() }
        return START_STICKY
    }

    override fun onDestroy() {
        loopJob?.cancel()
        scope.cancel()
        releaseLocks()
        Notifier.cancelCaptcha(this)
        ScannerHub.running.value = false
        ScannerHub.captcha.value = false
        ScannerHub.status.value = "Остановлен"
        prefs.serviceEnabled = false
        AppLog.i("Сервис остановлен")
        super.onDestroy()
    }

    // ------------------------------------------------------------------ основной цикл

    private suspend fun runLoop() {
        AppLog.ok("Сервис запущен. В базе ${seen.size()} ID")
        while (currentCoroutineContext().isActive) {
            val cfg = prefs.config()
            if (cfg.url.isBlank()) {
                setStatus("Не задан URL поиска")
                delay(10_000)
                continue
            }
            val driver = ScannerHub.driver
            if (driver == null) {
                setStatus("Жду WebView — откройте приложение")
                delay(10_000)
                continue
            }

            cycle++
            setStatus("Цикл #$cycle: загрузка страницы…")
            val result = try {
                driver.loadAndParse(cfg.url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ParseResult.Error(e.message ?: e.javaClass.simpleName)
            }

            when (result) {
                is ParseResult.Items -> process(result.items, cfg)
                is ParseResult.Captcha -> waitForCaptcha(cfg, result)
                is ParseResult.Error -> AppLog.e("Цикл #$cycle: ${result.message}")
            }

            val waitMs = cfg.intervalSec * 1000L + Random.nextLong(15_000, 40_001)
            val next = "следующая проверка через ${waitMs / 1000} с"
            AppLog.i(next.replaceFirstChar { it.uppercase() })
            setStatus("Отправлено всего: $totalSent · $next")
            delay(waitMs)
        }
    }

    private suspend fun process(items: List<Listing>, cfg: Config) {
        if (items.isEmpty()) {
            emptyStreak++
            AppLog.w("Цикл #$cycle: 0 карточек (подряд: $emptyStreak)")
            if (emptyStreak >= 3 && !emptyAlertSent) {
                emptyAlertSent = true
                tg(cfg, "⚠️ Avito Monitor: 3 цикла подряд не найдено ни одной карточки. Проверьте WebView на телефоне.")
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
            tg(cfg, "✅ <b>Avito Monitor запущен</b>\nТекущих объявлений в базе: ${items.size}\nДальше буду присылать только новые.")
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
                AppLog.i("Отсеяно (${reason}): ${item.title.take(40)}")
                continue
            }
            val r = TelegramClient.send(cfg.token, cfg.chatId, TelegramClient.formatListing(item))
            if (r.isSuccess) {
                seen.add(item.id)
                sent++
                totalSent++
            } else {
                AppLog.e("Telegram: ${r.exceptionOrNull()?.message}")
                failed = true
                break // не помечаем как увиденное — попробуем в следующем цикле
            }
            delay(1_100) // лимит Telegram ~1 сообщение/с в один чат
        }

        val msg = "Цикл #$cycle: спаршено ${items.size}, новых ${fresh.size}, отправлено $sent, отсеяно $filtered"
        if (failed) AppLog.w(msg) else AppLog.ok(msg)
    }

    private suspend fun waitForCaptcha(cfg: Config, first: ParseResult.Captcha) {
        ScannerHub.captcha.value = true
        AppLog.w("КАПЧА/блокировка: «${first.reason}». Таймер на паузе — пройдите проверку в WebView")
        setStatus("⚠️ Требуется ввод капчи!")
        Notifier.beep(this)
        Notifier.captcha(this)
        tg(cfg, "⚠️ Требуется ввод капчи на телефоне!", preview = false)

        var lastBeep = System.currentTimeMillis()
        var cleanProbes = 0
        while (currentCoroutineContext().isActive) {
            delay(10_000)
            val driver = ScannerHub.driver ?: continue
            val r = try { driver.probe() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }

            if (r is ParseResult.Items && r.items.isNotEmpty()) {
                AppLog.ok("Капча пройдена, на странице ${r.items.size} карточек")
                process(r.items, cfg)
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
        tg(cfg, "✅ Капча пройдена, мониторинг продолжается.", preview = false)
    }

    // ------------------------------------------------------------------ helpers

    private suspend fun tg(cfg: Config, html: String, preview: Boolean = false) {
        TelegramClient.send(cfg.token, cfg.chatId, html, preview)
            .onFailure { AppLog.e("Telegram: ${it.message}") }
    }

    private fun setStatus(text: String) {
        ScannerHub.status.value = text
        Notifier.update(this, text)
    }

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
    }
}
