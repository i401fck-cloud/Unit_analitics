# Avito Monitor (Android)

Фоновый сканер новых объявлений Avito 24/7 с уведомлениями в Telegram.
Страница загружается встроенным `WebView` (настоящий браузерный движок), объявления
достаются из DOM скриптом `app/src/main/assets/parser.js`, при капче включается пауза
и сигнал — капчу проходит человек прямо в окне приложения.

## Структура

```
.github/workflows/android.yml          # сборка APK в GitHub Actions
avito-monitor/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
└── app/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        ├── assets/parser.js            # JS-парсер карточек + детектор капчи
        ├── java/ru/avitomonitor/
        │   ├── MainActivity.kt         # экран, WebView, кнопки, лог, «вечный экран»
        │   ├── AvitoParserService.kt   # foreground-сервис, WakeLock, таймер, логика циклов
        │   ├── WebViewDriver.kt        # загрузка, скролл, evaluateJavascript, @JavascriptInterface
        │   ├── TelegramClient.kt       # sendMessage через OkHttp (HTML)
        │   ├── Notifier.kt             # уведомления, звук/вибрация при капче
        │   ├── SeenStore.kt            # последние 1000 ID (SharedPreferences)
        │   ├── Prefs.kt                # настройки, нормализация URL (s=104)
        │   ├── Models.kt               # Listing, ParseResult, Config + фильтры
        │   └── AppLog.kt               # живой лог + общее состояние
        └── res/ (layout, values, drawable, mipmap)
```

## Как получить APK (без Android Studio)

1. Залейте репозиторий на GitHub (папку `avito-monitor/` и `.github/workflows/android.yml`).
2. Откройте вкладку **Actions** → workflow **Build Avito Monitor APK**. Он запускается сам
   при каждом push, либо вручную кнопкой **Run workflow**.
3. Через ~5 минут откройте завершённый запуск → блок **Artifacts** → скачайте
   `AvitoMonitor-apk-N` (zip с `.apk` внутри).
4. Скопируйте APK на телефон и установите (разрешите «установку из неизвестных источников»).

### Стабильная подпись (рекомендуется)

Без секретов каждая сборка подписывается случайным debug-ключом раннера, поэтому новую
версию придётся ставить только после удаления старой (настройки и база ID сотрутся).
Чтобы обновляться поверх, один раз создайте ключ на любом компьютере с Java:

```bash
keytool -genkeypair -v -keystore avito.keystore -alias avito -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 avito.keystore > avito.keystore.b64     # macOS: base64 -i avito.keystore
```

и добавьте в **Settings → Secrets and variables → Actions**:
`KEYSTORE_BASE64` (содержимое .b64), `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` (`avito`),
`SIGNING_KEY_PASSWORD`. Сам keystore в репозиторий не кладите.

## Настройка

1. Создайте бота у [@BotFather](https://t.me/BotFather), получите токен.
   Напишите боту `/start`; Chat ID можно узнать у [@userinfobot](https://t.me/userinfobot)
   (для группы — добавьте бота в группу, ID начинается с `-100`).
2. В приложении заполните поля, нажмите **Проверить Telegram**.
3. **Показать WebView** → при желании войдите в аккаунт Avito → **Скрыть WebView**.
4. **Старт**. Разрешите уведомления и отключение оптимизации батареи.

## Как работает

- Цикл: загрузка URL (с `s=104`) → `onPageFinished` → пауза 3–5 с → 2–4 плавных скролла →
  `parser.js` → результат через `AndroidBridge.onResult()` (`@JavascriptInterface`).
- Пауза между циклами: `интервал` (мин. 30 с) + случайно 15–40 с.
- Первый проход по новому URL заполняет базу молча; дальше отправляются только новые ID
  (за цикл — не более 20, в хронологическом порядке). Отсеянные стоп-словами/ценой тоже
  помечаются как увиденные. Если Telegram недоступен, объявление отправится в следующем цикле.
- Капча: звук будильника + вибрация + уведомление + сообщение в Telegram, WebView раскрывается
  автоматически. Каждые 10 с страница проверяется без перезагрузки; как только карточки
  снова видны — таймер продолжает работу.
- 3 цикла подряд без карточек → предупреждение в Telegram (вероятно, сменилась вёрстка
  или страница не та).

## Советы для 24/7

- Держите телефон на зарядке, приложение — открытым на экране (кнопка «Назад» сворачивает,
  но не закрывает его). Экран не гаснет, яркость при скрытом WebView минимальная.
- Xiaomi/Huawei/Samsung: включите «Автозапуск», «Без ограничений» для батареи и закрепите
  приложение в списке недавних.
- Во время работы сервис сам перезагружает страницу каждый цикл — для входа в аккаунт
  нажмите **Стоп**, войдите, затем **Старт**.
- Не ставьте слишком маленький интервал: частые запросы быстрее приводят к капче.
