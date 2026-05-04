package org.ThreeDotsSierpinski;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList; // <-- ДОБАВЛЕНО
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.random.RandomGenerator;

/**
 * Класс для загрузки случайных чисел из ANU Quantum Numbers API.
 * При недоступности API автоматически переключается на L128X256MixRandom
 * (LXM family, Java 17+, период 2³⁸⁴, проходит TestU01 и PractRand).
 * При восстановлении API переключается обратно на квантовые числа.
 * Особенности:
 * - Неблокирующий getNextRandomNumber() — безопасен для вызова из EDT
 * - Exponential backoff при ошибках API
 * - Graceful degradation: QUANTUM → PSEUDO → QUANTUM
 * - Фоновая предзагрузка при снижении буфера ниже порога
 * - Кольцевой буфер (Ring Buffer) для истории потребленных чисел (фиксированный расход памяти)
 */
public class RNProvider {
    private static final Logger LOGGER = LoggerConfig.getLogger();

    /**
     * Исключение-маркер для мгновенного переключения в PSEUDO без ретраев.
     */
    private static class RateLimitException extends RuntimeException {
        RateLimitException(String message) {
            super(message);
        }
    }

    // ========================================================================
    // Режим работы
    // ========================================================================

    /**
     * Источник случайных чисел.
     */
    public enum Mode {
        /**
         * Квантовые числа от ANU API
         */
        QUANTUM,
        /**
         * Псевдослучайные числа от L128X256MixRandom (fallback)
         */
        PSEUDO
    }

    // ========================================================================
    // Настройки экземпляра
    // ========================================================================

    private final String apiUrl;
    private final String apiKey;
    private final String dataType;
    private final int arrayLength;
    private final int blockSize;
    private final int maxApiRequests;
    private final int connectTimeout;
    private final int readTimeout;
    private final int queueMinSize;
    private final int maxRetries;
    private final long initialBackoffMs;
    private final long maxBackoffMs;
    private final Sleeper sleeper;

    // ========================================================================
    // HTTP клиент, fallback PRNG и состояние
    // ========================================================================

    private final HttpClient httpClient;
    private final RandomGenerator fallbackRng;
    private final BlockingQueue<Integer> randomNumbersQueue;
    private final ObjectMapper objectMapper;
    private final RandomNumberProcessor numberProcessor;
    private int apiRequestCount = 0;
    private final List<RNLoadListener> listeners = new CopyOnWriteArrayList<>();

    // ========================================================================
    // RING BUFFER ДЛЯ ИСТОРИИ (Вместо List<Long>)
    // ========================================================================

    /**
     * Максимальный размер истории потребленных чисел (~0.8 МБ памяти)
     */
    private static final int HISTORY_MAX_SIZE = 100_000;

    /**
     * Сам массив-буфер
     */
    private final long[] consumedNumbersRing = new long[HISTORY_MAX_SIZE];

    /**
     * Указатель, куда писать следующее число
     */
    private volatile int ringWriteIndex = 0;

    /**
     * Счетчик реально сгенерированных чисел (чтобы не возвращать пустые нули из массива)
     */
    private volatile int totalConsumed = 0;
    private volatile boolean isLoading = false;
    private volatile boolean isReconnecting = false;
    private volatile boolean initialLoadComplete = false;
    private volatile String lastError = null;
    private volatile String fallbackReason = null;
    private volatile int consecutiveFailures = 0;
    private volatile Mode currentMode = Mode.QUANTUM;
    private volatile boolean isForcedPseudo = true; // По умолчанию всегда стартуем локально
    private volatile boolean apiKeyConfigured = true;

    /**
     * Сколько pseudo-чисел генерировать за одну «подгрузку»
     */
    private static final int PSEUDO_BATCH_SIZE = 1024;

    /**
     * После скольких pseudo-batch-ей пытаться переподключиться к API
     */
    private static final int RECONNECT_EVERY_N_BATCHES = 5;
    private volatile int pseudoBatchCount = 0;

    /**
     * Принудительно переключает в локальный режим (без запросов к API).
     */
    public void setForcedPseudo(boolean forced) {
        this.isForcedPseudo = forced;
        if (forced) {
            currentMode = Mode.PSEUDO;
            fallbackReason = "Manually forced to PSEUDO";
            isReconnecting = false;
            notifyModeChanged(Mode.PSEUDO);
        } else {
            // Юзер кликнул ВПРАВО (QUANTUM)
            if (!apiKeyConfigured) {
                // Нет ключа — не даём переключиться
                notifyModeChanged(Mode.PSEUDO); // Сигнализируем, что остались в PSEUDO
                return;
            }

            fallbackReason = null;
            currentMode = Mode.QUANTUM;
            isReconnecting = false;
            loadInitialDataAsync(); // Попытка подключиться
        }
    }

    public boolean isForcedPseudo() {
        return isForcedPseudo;
    }

    // ========================================================================
    // Sleeper и ProviderSettings (без изменений)
    // ========================================================================

    @FunctionalInterface
    interface Sleeper {
        void sleep(long ms) throws InterruptedException;
    }

    record ProviderSettings(
            String apiUrl, String apiKey, String dataType,
            int arrayLength, int blockSize, int maxApiRequests,
            int connectTimeout, int readTimeout, int queueMinSize,
            int maxRetries, long initialBackoffMs, long maxBackoffMs
    ) {
        static ProviderSettings fromConfig() {
            return new ProviderSettings(
                    Config.getString("api.url"),
                    Config.getString("api.key"),
                    Config.getString("api.data.type"),
                    Config.getInt("api.array.length"),
                    Config.getInt("api.block.size"),
                    Config.getInt("api.max.requests"),
                    Config.getInt("api.connect.timeout"),
                    Config.getInt("api.read.timeout"),
                    Config.getInt("random.queue.min.size"),
                    5, 1000L, 30000L
            );
        }
    }

    // ========================================================================
    // Конструкторы
    // ========================================================================

    public RNProvider() {
        this(ProviderSettings.fromConfig(), true, Thread::sleep);
    }

    RNProvider(ProviderSettings settings, boolean autoLoadOnStart, Sleeper sleeper) {
        this.apiUrl = settings.apiUrl();
        this.apiKey = settings.apiKey();
        this.dataType = settings.dataType();
        this.arrayLength = settings.arrayLength();
        this.blockSize = settings.blockSize();
        this.maxApiRequests = settings.maxApiRequests();
        this.connectTimeout = settings.connectTimeout();
        this.readTimeout = settings.readTimeout();
        this.queueMinSize = settings.queueMinSize();
        this.maxRetries = settings.maxRetries();
        this.initialBackoffMs = settings.initialBackoffMs();
        this.maxBackoffMs = settings.maxBackoffMs();
        this.sleeper = sleeper;

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeout))
                .build();

        // L128X256MixRandom: LXM family, период 2^384, 4-equidistributed
        // Самый качественный PRNG в стандартной Java (JEP 356)
        this.fallbackRng = RandomGenerator.of("L128X256MixRandom");

        randomNumbersQueue = new LinkedBlockingQueue<>();
        objectMapper = new ObjectMapper();
        numberProcessor = new RandomNumberProcessor();

// Проверка наличия API ключа
        if (apiKey == null || apiKey.isEmpty() || apiKey.startsWith("YOUR_")) {
            LOGGER.warning("API key is not configured. Falling back to pseudo-random mode (L128X256MixRandom).");
            apiKeyConfigured = false;
            activatePseudoMode("no API key"); // более короткий, консистентный текст
        } else if (autoLoadOnStart) {
            // Ключ есть!
            if (isForcedPseudo) {
                activatePseudoMode("Default local mode"); // Стартуем визуально как PSEUDO
                loadInitialDataAsync(); // Запускаем фоновую загрузку
            } else {
                loadInitialDataAsync();
            }
        }
    }

    // ========================================================================
    // Публичный API
    // ========================================================================

    public boolean waitForInitialData(long timeoutMs) {
        long start = System.currentTimeMillis();
        while (!initialLoadComplete && lastError == null &&
                (System.currentTimeMillis() - start) < timeoutMs) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return initialLoadComplete;
    }

    /**
     * Проверяет, был ли изначально сконфигурирован API ключ
     */
    public boolean isApiKeyConfigured() {
        return apiKeyConfigured;
    }

    public String getLastError() {
        return lastError;
    }

    public int getQueueSize() {
        return randomNumbersQueue.size();
    }

    /**
     * Текущий режим работы: QUANTUM или PSEUDO
     */
    public Mode getMode() {
        return currentMode;
    }

    /**
     * Возвращает причину последнего переключения в PSEUDO режим
     */
    public String getFallbackReason() {
        return fallbackReason;
    }

    /**
     * Возвращает неизменяемую копию всех доступных потребленных чисел (до HISTORY_MAX_SIZE).
     */
    public List<Long> getConsumedNumbers() {
        return getLastConsumedNumbers(HISTORY_MAX_SIZE);
    }

    /**
     * Возвращает последние N потребленных чисел (для UI без лагов).
     */
    public List<Long> getLastConsumedNumbers(int limit) {
        int actualSize = Math.min(limit, Math.min(totalConsumed, HISTORY_MAX_SIZE));
        if (actualSize <= 0) return List.of();

        long[] result = new long[actualSize];

        // Читаем буфер в обратном порядке (от самых новых к старым)
        int currentIdx = ringWriteIndex == 0 ? HISTORY_MAX_SIZE - 1 : ringWriteIndex - 1;

        for (int i = 0; i < actualSize; i++) {
            result[i] = consumedNumbersRing[currentIdx];
            currentIdx = currentIdx == 0 ? HISTORY_MAX_SIZE - 1 : currentIdx - 1;
        }

        // Разворачиваем массив, чтобы индекс 0 был самым старым из выборки
        for (int i = 0; i < actualSize / 2; i++) {
            long temp = result[i];
            result[i] = result[actualSize - 1 - i];
            result[actualSize - 1 - i] = temp;
        }

        // Конвертируем в List<Long> для совместимости с остальным кодом
        List<Long> list = new ArrayList<>(actualSize);
        for (long num : result) {
            list.add(num);
        }
        return list;
    }

    public void addDataLoadListener(RNLoadListener listener) {
        listeners.add(listener);
    }

    /**
     * Возвращает следующее случайное число.
     * НЕБЛОКИРУЮЩИЙ - безопасен для вызова из EDT.
     *
     * @return OptionalInt: число готово, или Empty (если QUANTUM буфер пуст и идет загрузка).
     */
    public OptionalInt getNextRandomNumber() {
        if (isForcedPseudo) {
            int pseudoNum = fallbackRng.nextInt(65536);
            addConsumedNumber(pseudoNum);
            return OptionalInt.of(pseudoNum);
        }

        Integer nextNumber = randomNumbersQueue.poll();
        if (nextNumber == null) {
            if (currentMode == Mode.PSEUDO) {
                fillQueueWithPseudo();
                int pseudoNum = fallbackRng.nextInt(65536);
                addConsumedNumber(pseudoNum);
                return OptionalInt.of(pseudoNum);
            }

            synchronized (this) {
                if (apiRequestCount >= maxApiRequests) {
                    activatePseudoMode("API request limit reached (" + maxApiRequests + ")");
                    int pseudoNum = fallbackRng.nextInt(65536);
                    addConsumedNumber(pseudoNum);
                    return OptionalInt.of(pseudoNum);
                }
            }

            loadInitialDataAsync();
            return OptionalInt.empty();
        }

        addConsumedNumber(nextNumber);

        if (randomNumbersQueue.size() < queueMinSize && apiRequestCount < maxApiRequests && !isLoading) {
            loadInitialDataAsync();
        }

        return OptionalInt.of(nextNumber);
    }

    /**
     * Фоновая задача: раз в 15 секунд пытается достучаться до API.
     * Если успешно — переключает обратно в QUANTUM и разблокирует UI.
     */
    private void startReconnectMonitor() {
        if (isReconnecting) return; // Если уже пингуем - не создаем еще один поток
        isReconnecting = true;

        Thread.startVirtualThread(() -> {
            while (isReconnecting && currentMode == Mode.PSEUDO) {
                try {
                    Thread.sleep(15000); // Ждем 15 секунд
                } catch (InterruptedException e) {
                    break;
                }

                if (!isReconnecting) break;

                LOGGER.info("Background reconnect attempt...");
                try {
                    loadInitialData();

                    isReconnecting = false;
                    consecutiveFailures = 0;
                    switchToQuantumMode();
                    notifyApiAvailability(true);
                    break;
                } catch (RateLimitException e) {
                    isReconnecting = false; // Суточный лимит - нечего пинговать
                    break;
                } catch (Exception e) {
                    LOGGER.fine("Reconnect failed, will retry in 15s: " + e.getMessage());
                }
            }
        });
    }

    public long getNextRandomNumberInRange(long min, long max) {
        int randomNum = getNextRandomNumber().orElseThrow();
        return numberProcessor.generateNumberInRange(randomNum, min, max);
    }

    public void shutdown() {
        isReconnecting = false; // <--- ДОБАВИТЬ
        LOGGER.info("RNProvider shutting down. Mode: " + currentMode
                + ", API requests: " + apiRequestCount
                + ", pseudo batches: " + pseudoBatchCount);
    }

    // ========================================================================
    // Package-private accessors
    // ========================================================================

    int getApiRequestCount() {
        return apiRequestCount;
    }

    boolean isInitialLoadComplete() {
        return initialLoadComplete;
    }

    void triggerLoad() {
        loadInitialDataAsync();
    }

    // ========================================================================
    // Внутренняя логика Ring Buffer
    // ========================================================================

    /**
     * Добавляет число в кольцевой буфер.
     * Потокобезопасно за счет атомарности записи в массив примитивов.
     */
    private void addConsumedNumber(long value) {
        consumedNumbersRing[ringWriteIndex] = value;
        ringWriteIndex = (ringWriteIndex + 1) % HISTORY_MAX_SIZE;
        totalConsumed++;
    }

    // ========================================================================
    // Pseudo-random fallback
    // ========================================================================

    private void activatePseudoMode(String reason) {
        // Всегда обновляем причину, даже если уже в PSEUDO
        this.fallbackReason = reason;

        if (currentMode == Mode.PSEUDO) {
            // Уже в PSEUDO — просто обновляем причину и дозаполняем буфер если нужно
            LOGGER.info("Updated PSEUDO fallback reason: " + reason);
            if (randomNumbersQueue.size() < queueMinSize) {
                fillQueueWithPseudo();
            }
            return;
        }

        currentMode = Mode.PSEUDO;
        lastError = null;
        LOGGER.info("Switched to PSEUDO mode (L128X256MixRandom). Reason: " + reason);

        fillQueueWithPseudo();
        initialLoadComplete = true;

        notifyModeChanged(Mode.PSEUDO);
        notifyLoadingCompleted();
    }

    private void fillQueueWithPseudo() {
        for (int i = 0; i < PSEUDO_BATCH_SIZE; i++) {
            randomNumbersQueue.add(fallbackRng.nextInt(65536));
        }
        pseudoBatchCount++;
        LOGGER.fine("Filled queue with " + PSEUDO_BATCH_SIZE + " pseudo-random numbers. "
                + "Queue size: " + randomNumbersQueue.size());
    }

    private void switchToQuantumMode() {
        // Кнопка активна по умолчанию (если есть ключ), замораживается только при handleLoadFailure.
        if (currentMode == Mode.QUANTUM) return;

        currentMode = Mode.QUANTUM;
        pseudoBatchCount = 0;
        LOGGER.info("Switched back to QUANTUM mode (ANU API).");
        notifyModeChanged(RNProvider.Mode.QUANTUM);
    }

    // ========================================================================
    // Внутренняя логика загрузки
    // ========================================================================

    private void loadInitialDataAsync() {
        synchronized (this) {
            if (isLoading || apiRequestCount >= maxApiRequests) {
                if (apiRequestCount >= maxApiRequests && currentMode == Mode.QUANTUM) {
                    activatePseudoMode("API request limit reached");
                }
                return;
            }

            // Разрешаем фоновую загрузку, если это старт по умолчанию (isForcedPseudo)
            if (currentMode == Mode.PSEUDO && !isForcedPseudo) {
                fillQueueWithPseudo();
                return;
            }

            isLoading = true;
        }

        CompletableFuture.runAsync(this::loadWithRetry, Thread::startVirtualThread)
                .exceptionally(ex -> {
                    LOGGER.log(Level.SEVERE, "Exception during data loading", ex);
                    handleLoadFailure("Exception: " + ex.getMessage());
                    synchronized (this) {
                        isLoading = false;
                    }
                    return null;
                });
    }

    private void loadWithRetry() {
        int retryCount = 0;

        try {
            while (retryCount <= maxRetries) {
                try {
                    loadInitialData();

                    consecutiveFailures = 0;
                    switchToQuantumMode();
                    checkAndLoadMore();
                    return;

                } catch (RateLimitException e) {
                    LOGGER.info("Rate limit (429) detected. Bypassing retries, activating fallback.");
                    handleLoadFailure("Суточный лимит исчерпан, переключаю на псевдослучайные числа.");
                    return;

                } catch (Exception e) {
                    retryCount++;
                    consecutiveFailures++;

                    if (retryCount > maxRetries) {
                        LOGGER.severe("All " + maxRetries + " retries failed: " + e.getMessage());
                        handleLoadFailure("API unavailable after " + maxRetries + " retries: " + e.getMessage());
                        return;
                    }

                    long backoffMs = calculateBackoff(retryCount);
                    LOGGER.warning(String.format("API failed (attempt %d/%d). Retry in %d ms. Error: %s",
                            retryCount, maxRetries, backoffMs, e.getMessage()));
                    notifyError("Retry " + retryCount + "/" + maxRetries + ": " + e.getMessage());

                    try {
                        sleeper.sleep(backoffMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        LOGGER.info("Retry interrupted.");
                        return;
                    }
                }
            }
        } finally {
            synchronized (this) {
                isLoading = false;
            }
        }
    }

    private void handleLoadFailure(String reason) {
        boolean isNetworkDown = reason.contains("Connection refused")
                || reason.contains("timed out")
                || reason.contains("UnknownHostException");
        boolean isRateLimit = reason.contains("429") || reason.contains("limit") || reason.contains("Limit Exceeded");

        // Rate limit = особый случай. Не пингуем бесконечно, но оставляем toggle enabled.
        if (isRateLimit) {
            // Не запускаем reconnect monitor для rate limit
            LOGGER.info("Rate limit active. Reconnect monitor disabled until manual retry.");
        }

        if (currentMode == Mode.QUANTUM) {
            activatePseudoMode(reason);
        } else {
            // Уже в PSEUDO — обновляем причину (см. activatePseudoMode выше)
            activatePseudoMode(reason);
        }

        // Запускаем фоновый пинг только для сетевых ошибок, НЕ для rate limit
        if (apiKeyConfigured && !isRateLimit && !isNetworkDown) {
            // Для "мягких" ошибок — пингуем
            startReconnectMonitor();
        }
        // Для isNetworkDown — тоже пингуем (сеть может восстановиться)
        if (apiKeyConfigured && isNetworkDown) {
            startReconnectMonitor();
        }
        // Для isRateLimit — НЕ пингуем (бессмысленно, лимит не сбросится через 15 сек)
    }

    long calculateBackoff(int retryAttempt) {
        long backoff = initialBackoffMs * (1L << (retryAttempt - 1));
        return Math.min(backoff, maxBackoffMs);
    }

    private String buildRequestUrl() {
        var url = new StringBuilder(apiUrl);
        url.append("?length=").append(Math.min(arrayLength, 1024));
        url.append("&type=").append(dataType);
        if ("hex16".equals(dataType)) {
            url.append("&size=").append(Math.min(blockSize, 1024));
        }
        return url.toString();
    }

    private void loadInitialData() throws Exception {
        notifyLoadingStarted();

        var requestUrl = buildRequestUrl();
        LOGGER.info("Sending request: " + requestUrl);

        var request = HttpRequest.newBuilder()
                .uri(URI.create(requestUrl))
                .header("x-api-key", apiKey)
                .timeout(Duration.ofMillis(readTimeout))
                .GET()
                .build();

        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        int statusCode = response.statusCode();

        if (statusCode != 200) {
            var errorBody = response.body();
            LOGGER.severe("HTTP error: " + statusCode + " - " + errorBody);

            if (statusCode == 429) {
                throw new RateLimitException(errorBody);
            }
            throw new IOException("HTTP error code: " + statusCode + " - " + errorBody);
        }

        var responseBody = response.body();
        LOGGER.info("Received response: " +
                responseBody.substring(0, Math.min(200, responseBody.length())) + "...");

        var rootNode = objectMapper.readTree(responseBody);

        if (rootNode.has("data")) {
            var dataNode = rootNode.get("data");

            if (!dataNode.isArray()) {
                throw new IOException("Invalid response format: 'data' is not an array.");
            }

            int loadedCount = 0;
            for (JsonNode element : dataNode) {
                if ("hex16".equals(dataType)) {
                    randomNumbersQueue.add(Integer.parseInt(element.asText(), 16));
                } else {
                    randomNumbersQueue.add(element.asInt());
                }
                loadedCount++;
            }

            LOGGER.info("Loaded " + loadedCount + " quantum random numbers. Queue: " + randomNumbersQueue.size());

            synchronized (this) {
                apiRequestCount++;
                initialLoadComplete = true;
                lastError = null;
            }

            notifyRawDataReceived(responseBody);
            notifyLoadingCompleted();

        } else if (rootNode.has("message")) {
            throw new IOException("API Error: " + rootNode.get("message").asText());
        } else {
            throw new IOException("Unexpected response from server.");
        }
    }

    private void checkAndLoadMore() {
        if (randomNumbersQueue.size() < queueMinSize && apiRequestCount < maxApiRequests && !isLoading) {
            loadInitialDataAsync();
        } else if (randomNumbersQueue.size() < queueMinSize && currentMode == Mode.PSEUDO) {
            fillQueueWithPseudo();
        }
    }

    // ========================================================================
    // Notifications
    // ========================================================================

    private void notifyLoadingStarted() {
        listeners.forEach(RNLoadListener::onLoadingStarted);
    }

    private void notifyLoadingCompleted() {
        listeners.forEach(RNLoadListener::onLoadingCompleted);
    }

    private void notifyError(String errorMessage) {
        listeners.forEach(listener -> listener.onError(errorMessage));
    }

    private void notifyRawDataReceived(String rawData) {
        listeners.forEach(listener -> listener.onRawDataReceived(rawData));
    }

    private void notifyModeChanged(Mode mode) {
        listeners.forEach(listener -> listener.onModeChanged(mode));
    }

    private void notifyApiAvailability(boolean isAvailable) {
        listeners.forEach(listener -> listener.onApiAvailabilityChanged(isAvailable));
    }
}
