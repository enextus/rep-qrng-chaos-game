# rep-qrng-chaos-game

Java Swing-приложение для визуализации случайных чисел в нескольких режимах: **Sierpinski Triangle / Chaos Game**, **DLA / Brownian Tree** и **Voronoi Mosaic**.

Проект использует два источника случайности:

1. **QUANTUM** — истинные случайные числа из **ANU Quantum Random Numbers API**;
2. **PSEUDO** — локальный fallback на `L128X256MixRandom`, если API недоступен, не задан ключ или исчерпан лимит.

Главная идея проекта: показать, как последовательности случайных чисел превращаются в видимые структуры — фракталы, диффузионные кластеры и мозаики Вороного.

Важно: приложение **не доказывает** «истинную случайность» генератора. Оно даёт наглядную визуализацию и прикладные **statistical sanity checks** по тем числам, которые реально были использованы в текущей сессии.

---

## Что умеет приложение

- показывает стартовое окно выбора режима визуализации;
- поддерживает loop workflow: выбор режима → окно визуализации → **Выйти** → возврат к выбору режима;
- корректно работает с несколькими мониторами:
  - первое окно открывается на мониторе, где пользователь запускает приложение;
  - если окно выбора режима перенести на другой монитор, визуализация откроется там же;
  - после закрытия окна визуализации выбор режима возвращается на тот монитор, где была закрыта визуализация;
- рисует визуализацию в реальном времени через Swing/AWT;
- поддерживает режимы:
  - **Sierpinski Triangle**;
  - **DLA / Brownian Tree**;
  - **Voronoi Mosaic**;
- получает числа из ANU QRNG API по HTTP с заголовком `x-api-key`;
- поддерживает локальный буфер случайных чисел и фоновую дозагрузку;
- не блокирует EDT при получении новых чисел;
- автоматически переключается в **PSEUDO** при недоступности API, отсутствии ключа или исчерпании лимита;
- пытается вернуться в **QUANTUM** после восстановления внешнего источника;
- показывает текущий режим генерации в статусной строке;
- поддерживает **Play / Stop** для паузы и продолжения анимации;
- поддерживает переключатель источника RNG: **QUANTUM / PSEUDO**;
- отображает уже использованные числа в правой части окна;
- числовая таблица адаптируется к текущей высоте окна;
- запускает встроенные статистические тесты кнопкой **Test RNG**;
- показывает результаты тестов в цветном диалоге с уровнями качества `STRONG / MARGINAL / FAIL`;
- сохраняет текущую визуализацию в **PNG**;
- поддерживает mode-specific controls, например переключатель **Метки ВКЛ./ВЫКЛ.** в режиме Voronoi;
- пишет логи в файл `logs/app.log` и в консоль.

---

## Режимы визуализации

### Sierpinski Triangle

Классический Chaos Game.

Алгоритм:

1. берётся текущая точка;
2. очередное случайное число выбирает одну из трёх вершин треугольника;
3. новая точка ставится на середину отрезка между текущей точкой и выбранной вершиной;
4. процесс повторяется много раз.

В результате возникает треугольник Серпинского.

---

### DLA / Brownian Tree

Diffusion-Limited Aggregation.

Частицы случайно блуждают по полю и прилипают к уже существующему кластеру. Получается структура, похожая на кораллы, молнии, кристаллы или рост колоний.

Особенности режима:

- чёрный фон;
- параллельные walkers;
- ограничение lifespan для блуждающих частиц;
- spawn/teleport logic для экономии случайных чисел;
- HSB-градиент от тёплого центра к холодной периферии;
- размер точки уменьшается с расстоянием от центра.

---

### Voronoi Mosaic

Диаграмма Вороного с Lloyd's Relaxation.

Случайные точки становятся центрами ячеек. Каждая новая точка меняет мозаику. Периодически запускается Lloyd relaxation, который сдвигает точки к центроидам ячеек и делает мозаику более равномерной и органичной.

Особенности режима:

- цветные многоугольные ячейки;
- затемнённые границы между ячейками;
- белые метки центров ячеек;
- переключатель **Метки ВКЛ./ВЫКЛ.** для динамического скрытия/показа белых крестиков;
- redraw текущего состояния без добавления новых random seeds.

---

## Технологический стек

- **Java 25**
- **Swing / AWT**
- **Maven**
- **Jackson Databind** для разбора JSON
- **FlatLaf** для внешнего вида Swing UI
- **JUnit 5** для тестов
- **Mockito** для части тестовой инфраструктуры
- встроенный `com.sun.net.httpserver.HttpServer` для integration-тестов `RNProvider`
- **ANU Quantum Random Numbers API** как внешний источник данных

---

## Как это работает

### 1. Источник случайных чисел

`RNProvider` загружает числа из ANU API. По умолчанию проект запрашивает:

- тип данных: `uint16`;
- длину массива: `1024`;
- минимальный локальный буфер: `100`;
- максимум запросов за сессию: `100`.

При временных ошибках используется retry с exponential backoff:

- 1 секунда;
- 2 секунды;
- 4 секунды;
- 8 секунд;
- 16 секунд;
- верхняя граница задержки: 30 секунд.

Если загрузка не удалась, API key не задан или лимит запросов исчерпан, `RNProvider` не останавливает приложение. Он переключается в `PSEUDO` и начинает выдавать числа из локального генератора `L128X256MixRandom`.

После нескольких pseudo-batch-циклов провайдер пытается снова обратиться к ANU API. Если загрузка успешна, приложение возвращается в `QUANTUM`.

---

### 2. Нормализация диапазона

`RandomNumberProcessor` преобразует числа из ответа API в нужный диапазон. Для текущей конфигурации основной рабочий диапазон:

```text
0..65535
```

---

### 3. Абстракция режимов визуализации

Все режимы реализуют интерфейс `VisualizationMode`.

Режим отвечает за:

- уникальный id;
- имя;
- описание;
- иконку;
- инициализацию canvas;
- один шаг анимации;
- количество нарисованных точек;
- количество использованных случайных чисел;
- признак recolor-анимации;
- признак тёмного фона;
- при необходимости — собственные UI controls;
- при необходимости — redraw текущего состояния.

Реестр доступных режимов находится в:

```java
VisualizationMode.allModes()
```

---

### 4. Отрисовка

`DotController` работает как Swing-панель, которая:

- хранит `offscreenImage`;
- обновляет изображение через `javax.swing.Timer`;
- делегирует шаг визуализации выбранному `VisualizationMode`;
- выполняет работу с `offscreenImage` только на EDT;
- поддерживает краткую recolor-анимацию для режимов, которым она нужна;
- показывает счётчик точек;
- показывает текущий режим RNG;
- отображает стек использованных чисел;
- адаптирует таблицу чисел под реальную высоту окна;
- поддерживает `refreshVisualization()` для перерисовки текущего режима без добавления новых чисел;
- останавливает внутренние timers через `shutdown()`.

---

### 5. Workflow приложения

Стартовый flow:

```text
App.main()
    ↓
ModeSelectionDialog
    ↓
Visualization JFrame + DotController
    ↓
Выйти / закрытие окна
    ↓
ModeSelectionDialog
```

`App` управляет:

- стартом FlatLaf;
- выбором режима;
- созданием окна визуализации;
- status bar;
- кнопками управления;
- RNG toggle;
- запуском статистических тестов;
- сохранением PNG;
- кнопкой **Выйти**;
- multi-monitor positioning;
- возвратом к выбору режима после завершения визуализации.

Закрытие окна через `X` обрабатывается так же, как кнопка **Выйти**: визуализация останавливается, ресурсы очищаются, приложение возвращается к выбору режима.

---

### 6. Проверка выборки

После накопления хотя бы 10 чисел можно запустить статистические проверки кнопкой **Test RNG**.

Tooltip кнопки:

```text
Test values quality
```

Результаты выводятся в цветном диалоге:

- **зелёный** — `STRONG`;
- **жёлтый** — `MARGINAL`;
- **красный** — `FAIL`.

Для p-value тестов высокий запас до порога помечается как `STRONG`, близость к порогу — как `MARGINAL`. Для K-S и χ² логика аналогична, но ориентируется на расстояние до критического значения.

---

## Архитектура проекта

### Основные классы

- **`App`** — точка входа, сборка GUI, mode workflow loop, multi-monitor логика, кнопки управления, статистические тесты, сохранение PNG.
- **`ModeSelectionDialog`** — окно выбора режима визуализации с карточками доступных режимов.
- **`VisualizationMode`** — общий интерфейс режимов визуализации и registry всех доступных режимов.
- **`DotController`** — центральная панель визуализации; управляет timer-отрисовкой, offscreen-буфером, счётчиками, числовым стеком и refresh/redraw.
- **`SierpinskiMode`** — режим Chaos Game / Sierpinski Triangle.
- **`DLAMode`** — режим Diffusion-Limited Aggregation / Brownian Tree.
- **`VoronoiMode`** — режим Voronoi Mosaic с Lloyd's Relaxation и toggle-метками.
- **`Dot`** — immutable `record`, безопасно копирующий `Point`.
- **`SierpinskiAlgorithm`** — чистая математическая логика Chaos Game без зависимости от Swing.
- **`RNProvider`** — сетевой клиент и буфер случайных чисел из ANU API с fallback-режимом `QUANTUM → PSEUDO → QUANTUM`.
- **`RandomNumberProcessor`** — преобразование входных чисел/HEX в целевой диапазон.
- **`Config`** — загрузка конфигурации из environment, `.env` и `config.properties`.
- **`LoggerConfig`** — настройка файлового и консольного логирования.
- **`RNLoadListener`** / **`RNLoadListenerImpl`** — уведомления о загрузке, ошибках, смене режима и состоянии RNG.
- **`TestResult`** — record результата теста с уровнем качества `STRONG / MARGINAL / FAIL`.

---

## Runtime-тесты случайности

`RandomnessTestSuite` запускает 4 теста:

- **`KolmogorovSmirnovTest`** — проверка отклонения эмпирического распределения от равномерного;
- **`FrequencyBitTest`** — битовый частотный тест, оценивающий баланс `0/1`;
- **`ChiSquareUniformityTest`** — χ²-проверка равномерности по корзинам;
- **`RunsBitTest`** — тест серий по битовой последовательности.

Результат каждого теста возвращается как `TestResult`.

---

## Структура проекта

```text
rep-qrng-chaos-game/
├── Readme.md
├── pom.xml
├── logs/
│   └── app.log
├── src/
│   ├── main/
│   │   ├── java/org/ThreeDotsSierpinski/
│   │   │   ├── App.java
│   │   │   ├── ChiSquareUniformityTest.java
│   │   │   ├── Config.java
│   │   │   ├── DLAMode.java
│   │   │   ├── Dot.java
│   │   │   ├── DotController.java
│   │   │   ├── FrequencyBitTest.java
│   │   │   ├── KolmogorovSmirnovTest.java
│   │   │   ├── LoggerConfig.java
│   │   │   ├── MathUtils.java
│   │   │   ├── ModeSelectionDialog.java
│   │   │   ├── RNLoadListener.java
│   │   │   ├── RNLoadListenerImpl.java
│   │   │   ├── RNProvider.java
│   │   │   ├── RandomNumberProcessor.java
│   │   │   ├── RandomnessTest.java
│   │   │   ├── RandomnessTestSuite.java
│   │   │   ├── RunsBitTest.java
│   │   │   ├── SierpinskiAlgorithm.java
│   │   │   ├── SierpinskiMode.java
│   │   │   ├── TestResult.java
│   │   │   ├── VisualizationMode.java
│   │   │   └── VoronoiMode.java
│   │   └── resources/
│   │       └── config.properties
│   └── test/
│       └── java/org/ThreeDotsSierpinski/
│           ├── ChiSquareUniformityTestTest.java
│           ├── ConfigTest.java
│           ├── DotTest.java
│           ├── FrequencyBitTestTest.java
│           ├── KolmogorovSmirnovTestUnitTest.java
│           ├── LoggerConfigTest.java
│           ├── NISTRandomnessTest.java
│           ├── NISTRandomnessTestUnitTest.java
│           ├── README.md
│           ├── RNProviderIntegrationTest.java
│           ├── RandomNumberProcessorTest.java
│           ├── RandomnessTestSuiteTest.java
│           ├── RunsBitTestTest.java
│           ├── SierpinskiAlgorithmTest.java
│           ├── StatisticalRandomnessTest.java
│           └── TestResultTest.java
```

---

## Требования

- **JDK 25**
- **Maven 3.8+**
- действующий API key для **ANU Quantum Random Numbers API** для режима `QUANTUM`
- доступ в интернет для живого источника квантовых чисел

В `pom.xml` явно указаны:

```text
maven.compiler.source=25
maven.compiler.target=25
```

Даже без рабочего API приложение способно стартовать в режиме `PSEUDO`.

---

## Настройка API-ключа

Проект ищет настройки в таком порядке:

1. переменные окружения;
2. файл `.env` в корне проекта;
3. `src/main/resources/config.properties`.

Правило преобразования имён:

| Config key | Environment variable |
|---|---|
| `api.key` | `QRNG_API_KEY` |
| `api.url` | `QRNG_API_URL` |
| `panel.size.width` | `QRNG_PANEL_SIZE_WIDTH` |

### Linux/macOS

```bash
export QRNG_API_KEY=your_real_anu_api_key
mvn clean package
```

### Windows PowerShell

```powershell
$env:QRNG_API_KEY="your_real_anu_api_key"
mvn clean package
```

---

## Сборка и запуск

### Сборка

```bash
mvn clean package
```

После сборки Maven Assembly Plugin создаёт fat JAR:

```text
target/rep-qrng-chaos-game-1.0-SNAPSHOT-jar-with-dependencies.jar
```

### Запуск собранного JAR

```bash
java -jar target/rep-qrng-chaos-game-1.0-SNAPSHOT-jar-with-dependencies.jar
```

### Запуск из IDE

Main class:

```text
org.ThreeDotsSierpinski.App
```

---

## Конфигурация по умолчанию

Основные значения из `config.properties`:

| Параметр | Значение по умолчанию | Назначение |
|---|---:|---|
| `api.url` | `https://api.quantumnumbers.anu.edu.au` | Базовый URL API |
| `api.data.type` | `uint16` | Тип случайных данных |
| `api.array.length` | `1024` | Число элементов в одном запросе |
| `api.block.size` | `2` | Размер логического блока |
| `api.max.requests` | `100` | Максимум API-запросов за сессию |
| `api.connect.timeout` | `10000` | Таймаут соединения, мс |
| `api.read.timeout` | `15000` | Таймаут чтения, мс |
| `random.queue.min.size` | `100` | Порог дозагрузки буфера |
| `random.min.value` | `0` | Нижняя граница диапазона |
| `random.max.value` | `65535` | Верхняя граница диапазона |
| `panel.size.width` | `600` | Базовая ширина области рисования |
| `panel.size.height` | `600` | Базовая высота области рисования |
| `dot.size` | `2` | Размер точки |
| `timer.delay` | `150` | Интервал таймера, мс |
| `dots.per.update` | `5` | Число новых точек за тик для Sierpinski |
| `window.scale.width` | `1.5` | Масштаб окна по ширине |
| `window.scale.height` | `1.1` | Масштаб окна по высоте |
| `column.width` | `52` | Ширина колонки в числовом стеке |
| `row.height` | `14` | Высота строки |
| `column.spacing` | `8` | Отступ между колонками |
| `max.columns` | `5` | Максимум колонок со значениями |
| `log.file.name` | `logs/app.log` | Путь к лог-файлу |
| `log.level` | `INFO` | Уровень логирования |

---

## GUI-поведение

После запуска приложение:

1. показывает окно выбора режима;
2. пользователь выбирает визуализацию;
3. открывается окно выбранного режима;
4. начинается подготовка источника случайных чисел;
5. после появления данных активируется **Play**;
6. пользователь запускает/останавливает анимацию;
7. при необходимости переключает RNG;
8. при необходимости запускает **Test RNG**;
9. при необходимости сохраняет PNG;
10. нажимает **Выйти**;
11. приложение возвращается к выбору режима.

Если буфер временно пуст, анимация не блокируется: контроллер пропускает тик и ждёт дозагрузки данных в фоне.

Если внешний источник недоступен, приложение продолжает работу в fallback-режиме.

---

## Multi-monitor поведение

Приложение учитывает multi-monitor setup.

Правила:

- при старте выбирается монитор под курсором мыши;
- `ModeSelectionDialog` открывается на выбранном мониторе;
- если пользователь переносит окно выбора режима на другой монитор, окно визуализации открывается на новом текущем мониторе;
- если пользователь переносит окно визуализации на другой монитор и нажимает **Выйти**, следующий `ModeSelectionDialog` откроется на этом мониторе;
- при определении монитора используется наибольшее пересечение bounds окна с bounds монитора.

---

## Управление в окне визуализации

Основные элементы:

| Control | Назначение |
|---|---|
| **Play / Stop** | запуск и пауза визуализации |
| **PSEUDO / QUANTUM** | переключение источника RNG |
| **Test RNG** | запуск тестов качества использованных чисел |
| **Save PNG** | сохранение текущей визуализации |
| **Выйти** | завершение текущей визуализации и возврат к выбору режима |

Mode-specific controls:

| Режим | Control | Назначение |
|---|---|---|
| `VoronoiMode` | **Метки ВКЛ./ВЫКЛ.** | показать или скрыть белые крестики в центрах ячеек |

Нижняя панель сделана адаптивной: кнопка **Выйти** остаётся видимой даже при небольшой ширине окна.

---

## Логирование

Логгер:

- создаёт директорию `logs/`, если её ещё нет;
- пишет в файл `logs/app.log`;
- дублирует важные сообщения в консоль;
- корректно деградирует до console-only режима, если файловый лог недоступен.

Полезные события в логах:

- старт приложения;
- выбранный режим визуализации;
- старт и остановка анимации;
- переходы между `QUANTUM` и `PSEUDO`;
- причины fallback;
- ошибки чтения API;
- выбранные monitor bounds;
- возврат из visualization window в mode selection;
- сохранение PNG.

---

## Тесты

В проекте есть unit-, component- и integration-тесты на **JUnit 5**.

### Что покрыто

- `ConfigTest` — загрузка конфигурации и преобразование ключей;
- `DotTest` — корректность immutable `Dot` record;
- `RandomNumberProcessorTest` — диапазоны, HEX, равномерный mapping;
- `KolmogorovSmirnovTestUnitTest` — корректность K-S теста;
- `FrequencyBitTestTest` — частотный битовый тест;
- `RunsBitTestTest` — тест серий;
- `ChiSquareUniformityTestTest` — χ²-проверка;
- `RandomnessTestSuiteTest` — orchestration набора тестов;
- `TestResultTest` — поведение record `TestResult` и quality-семантика;
- `LoggerConfigTest` — инициализация логгера;
- `SierpinskiAlgorithmTest` — математика Chaos Game;
- `StatisticalRandomnessTest` — статистические свойства тестовых выборок;
- `NISTRandomnessTest` и `NISTRandomnessTestUnitTest` — дополнительные проверки и вспомогательные эксперименты;
- `RNProviderIntegrationTest` — интеграционное тестирование `RNProvider` с локальным mock HTTP-сервером.

### Что проверяет `RNProviderIntegrationTest`

- успешную загрузку `uint16` и `hex16`;
- HTTP-заголовок `x-api-key`;
- query parameters `length`, `type`, `size`;
- retry после `HTTP 500`;
- исчерпание retry;
- malformed JSON;
- API message errors;
- поведение при пустом буфере;
- лимит `maxApiRequests`;
- listener callbacks;
- `calculateBackoff`;
- отсутствие API key;
- `waitForInitialData`;
- фоновую подгрузку при `queue < minSize`.

### Теги тестов

`pom.xml` настроен под JUnit 5 tags через Surefire.

```bash
mvn test
mvn test -Dgroups=fast
mvn test -Dgroups=integration
mvn test -DexcludedGroups=slow
```

### Запуск конкретных тестов

```bash
mvn -Dtest=RNProviderIntegrationTest test
mvn -Dtest=RandomnessTestSuiteTest test
mvn -Dtest=TestResultTest test
```

---

## Ограничения и важные замечания

- для реального квантового режима нужен рабочий ANU API key;
- работа в `QUANTUM` зависит от сети и доступности внешнего API;
- лимиты ANU по запросам и битам влияют на длительность непрерывной сессии;
- fallback-режим сохраняет непрерывность работы приложения, но в этот момент используются псевдослучайные числа;
- статистические тесты встроены для практической оценки конкретной выборки, а не для криптографической сертификации;
- GUI и live API взаимодействие чувствительны к окружению;
- каталог `target/` обычно не стоит хранить в git;
- `logs/app.log` обычно тоже не нужно коммитить, кроме специальных debug-сценариев.

---

## Типовой сценарий использования

1. получить API key от ANU;
2. положить ключ в `.env` или переменную окружения `QRNG_API_KEY`;
3. собрать проект;
4. запустить приложение;
5. выбрать режим визуализации;
6. дождаться готовности RNG;
7. нажать **Play**;
8. наблюдать построение структуры;
9. при необходимости переключить RNG;
10. при необходимости запустить **Test RNG**;
11. при необходимости сохранить изображение через **Save PNG**;
12. нажать **Выйти** и выбрать другой режим.

---

## Краткое резюме

`rep-qrng-chaos-game` — учебно-практический Java Swing-проект, где истинно случайные числа из внешнего QRNG API и локальный pseudo-random fallback используются как двигатель для живых визуализаций:

- фрактала Серпинского;
- DLA / Brownian Tree;
- Voronoi Mosaic.

Проект включает mode selection workflow, multi-monitor-friendly UI, адаптивный status bar, статистическую диагностику выборки, PNG export, integration-тесты сетевого провайдера и аккуратную архитектуру режимов визуализации через `VisualizationMode`.
