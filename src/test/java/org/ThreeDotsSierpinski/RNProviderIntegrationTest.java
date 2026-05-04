package org.ThreeDotsSierpinski;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.OptionalInt;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Интеграционные тесты для RNProvider с локальным mock HTTP-сервером.
 * Используют com.sun.net.httpserver.HttpServer — никаких внешних зависимостей.
 * Sleeper заменён на no-op для мгновенного прохождения retry-логики.
 * Покрытие:
 * - Успешная загрузка uint16 / hex16
 * - Проверка HTTP-заголовка x-api-key
 * - Проверка query parameters (length, type, size)
 * - Retry после HTTP 500 → успех
 * - Исчерпание retry → lastError
 * - Malformed JSON
 * - API message error ({"message": "..."})
 * - Пустой буфер → NoSuchElementException
 * - Лимит maxApiRequests
 * - Listener callbacks (started, completed, error, rawData)
 * - calculateBackoff (exponential + cap)
 * - API key не настроен → lastError
 * - waitForInitialData с autoLoad
 * - Фоновая подгрузка при queue < minSize
 */
@DisplayName("RNProvider — интеграционные тесты с mock HTTP")
@Tag("integration")
class RNProviderIntegrationTest {

    private HttpServer mockServer;
    private int mockPort;
    private String baseUrl;

    // No-op sleeper для мгновенных тестов (вместо реального Thread.sleep)
    private static final RNProvider.Sleeper INSTANT_SLEEPER = ms -> {
    };

    // ========================================================================
    // Lifecycle: поднимаем/останавливаем mock-сервер для каждого теста
    // ========================================================================

    @BeforeEach
    void startMockServer() throws IOException {
        mockServer = HttpServer.create(new InetSocketAddress(0), 0);
        mockPort = mockServer.getAddress().getPort();
        baseUrl = "http://localhost:" + mockPort;
        mockServer.start();
    }

    @AfterEach
    void stopMockServer() {
        if (mockServer != null) {
            mockServer.stop(0);
        }
    }

    // ========================================================================
    // Хелперы
    // ========================================================================

    /**
     * Создаёт ProviderSettings с mock URL и разумными дефолтами для тестов.
     */
    private RNProvider.ProviderSettings testSettings() {
        return testSettings("uint16");
    }

    private RNProvider.ProviderSettings testSettings(String dataType) {
        return new RNProvider.ProviderSettings(
                baseUrl,          // apiUrl → mock server
                "test-api-key",   // apiKey
                dataType,         // dataType
                5,                // arrayLength (маленький для тестов)
                2,                // blockSize
                100,              // maxApiRequests
                2000,             // connectTimeout ms
                2000,             // readTimeout ms
                3,                // queueMinSize
                3,                // maxRetries (меньше чем в продакшене)
                1L,               // initialBackoffMs (1ms для скорости)
                10L               // maxBackoffMs
        );
    }

    /**
     * Создаёт RNProvider без автозагрузки (autoLoadOnStart=false).
     */
    private RNProvider createProvider() {
        return new RNProvider(testSettings(), false, INSTANT_SLEEPER);
    }

    private RNProvider createProvider(String dataType) {
        return new RNProvider(testSettings(dataType), false, INSTANT_SLEEPER);
    }

    private RNProvider createProvider(RNProvider.ProviderSettings settings) {
        return new RNProvider(settings, false, INSTANT_SLEEPER);
    }

    /**
     * Создаёт провайдера в QUANTUM-режиме (сбрасывает forced pseudo) и запускает загрузку.
     * Используется в тестах, которые проверяют работу с API.
     */
    private RNProvider createQuantumProvider() {
        return createQuantumProvider(testSettings());
    }

    private RNProvider createQuantumProvider(RNProvider.ProviderSettings settings) {
        RNProvider provider = new RNProvider(settings, false, INSTANT_SLEEPER);
        provider.setForcedPseudo(false); // Переключаем в QUANTUM и запускаем загрузку
        return provider;
    }

    /**
     * Настраивает mock-сервер для ответа фиксированным JSON.
     */
    private void mockSuccess(String responseJson) {
        mockServer.createContext("/", exchange -> {
            sendResponse(exchange, 200, responseJson);
        });
    }

    /**
     * Настраивает mock для ответа определённым HTTP-кодом.
     */
    private void mockStatus(int statusCode, String body) {
        mockServer.createContext("/", exchange -> {
            sendResponse(exchange, statusCode, body);
        });
    }

    private void sendResponse(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /**
     * Записывающий listener для проверки callback-ов.
     */
    private static class RecordingListener implements RNLoadListener {
        final List<String> events = new CopyOnWriteArrayList<>();
        final List<String> errors = new CopyOnWriteArrayList<>();
        final List<String> rawDataList = new CopyOnWriteArrayList<>();
        volatile CountDownLatch completedLatch = new CountDownLatch(1);
        volatile CountDownLatch errorLatch = new CountDownLatch(1);

        @Override
        public void onLoadingStarted() {
            events.add("started");
        }

        @Override
        public void onLoadingCompleted() {
            events.add("completed");
            completedLatch.countDown();
        }

        @Override
        public void onError(String errorMessage) {
            events.add("error");
            errors.add(errorMessage);
            errorLatch.countDown();
        }

        @Override
        public void onRawDataReceived(String rawData) {
            events.add("rawData");
            rawDataList.add(rawData);
        }
    }

    // ========================================================================
    // Тесты: Успешная загрузка
    // ========================================================================

    @Nested
    @DisplayName("Успешная загрузка данных")
    class SuccessfulLoadTests {

        @Test
        @DisplayName("uint16 — парсит массив целых чисел")
        void testLoadUint16() throws Exception {
            mockSuccess("{\"data\":[100,200,300,400,500]}");
            RNProvider provider = createQuantumProvider();

            assertTrue(provider.waitForInitialData(5000), "Должен загрузить данные за 5 сек");

            assertEquals(100, provider.getNextRandomNumber().getAsInt());
            assertEquals(200, provider.getNextRandomNumber().getAsInt());
            assertEquals(300, provider.getNextRandomNumber().getAsInt());
        }

        @Test
        @DisplayName("hex16 — парсит шестнадцатеричные строки")
        void testLoadHex16() throws Exception {
            // "FF" = 255, "1A2B" = 6699, "0000" = 0
            mockSuccess("{\"data\":[\"FF\",\"1A2B\",\"0000\"]}");
            RNProvider provider = createQuantumProvider(testSettings("hex16"));

            assertTrue(provider.waitForInitialData(5000));

            assertEquals(255, provider.getNextRandomNumber().getAsInt());
            assertEquals(6699, provider.getNextRandomNumber().getAsInt());
            assertEquals(0, provider.getNextRandomNumber().getAsInt());
        }

        @Test
        @DisplayName("Большой массив данных загружается полностью")
        void testLoadLargeArray() throws Exception {
            StringBuilder json = new StringBuilder("{\"data\":[");
            for (int i = 0; i < 100; i++) {
                if (i > 0) json.append(",");
                json.append(i * 100);
            }
            json.append("]}");

            mockSuccess(json.toString());
            RNProvider provider = createQuantumProvider(testSettings());

            assertTrue(provider.waitForInitialData(5000));
            assertEquals(100, provider.getQueueSize());
        }
    }

    // ========================================================================
    // Тесты: HTTP-запрос (заголовки, параметры)
    // ========================================================================

    @Nested
    @DisplayName("HTTP-запрос: заголовки и параметры")
    class HttpRequestTests {

        @Test
        @DisplayName("Передаёт x-api-key в заголовке запроса")
        void testApiKeyHeader() throws Exception {
            AtomicReference<String> capturedApiKey = new AtomicReference<>();

            mockServer.createContext("/", exchange -> {
                capturedApiKey.set(exchange.getRequestHeaders().getFirst("x-api-key"));
                sendResponse(exchange, 200, "{\"data\":[42]}");
            });

            RNProvider provider = createQuantumProvider(testSettings());
            assertTrue(provider.waitForInitialData(5000));

            assertEquals("test-api-key", capturedApiKey.get(),
                    "Заголовок x-api-key должен содержать ключ из настроек");
        }

        @Test
        @DisplayName("Query params содержат length и type для uint16")
        void testQueryParamsUint16() throws Exception {
            AtomicReference<String> capturedQuery = new AtomicReference<>();

            mockServer.createContext("/", exchange -> {
                capturedQuery.set(exchange.getRequestURI().getQuery());
                sendResponse(exchange, 200, "{\"data\":[1]}");
            });

            RNProvider provider = createQuantumProvider(testSettings("uint16"));
            assertTrue(provider.waitForInitialData(5000));

            String query = capturedQuery.get();
            assertNotNull(query, "Query string не должен быть null");
            assertTrue(query.contains("length=5"), "Должен содержать length=5, получено: " + query);
            assertTrue(query.contains("type=uint16"), "Должен содержать type=uint16, получено: " + query);
            assertFalse(query.contains("size="), "uint16 не должен содержать size, получено: " + query);
        }

        @Test
        @DisplayName("Query params содержат length, type и size для hex16")
        void testQueryParamsHex16() throws Exception {
            AtomicReference<String> capturedQuery = new AtomicReference<>();

            mockServer.createContext("/", exchange -> {
                capturedQuery.set(exchange.getRequestURI().getQuery());
                sendResponse(exchange, 200, "{\"data\":[\"ABCD\"]}");
            });

            RNProvider provider = createQuantumProvider(testSettings("hex16"));
            assertTrue(provider.waitForInitialData(5000));

            String query = capturedQuery.get();
            assertNotNull(query, "Query string не должен быть null");
            assertTrue(query.contains("type=hex16"), "Должен содержать type=hex16");
            assertTrue(query.contains("size=2"), "hex16 должен содержать size=2");
        }
    }

    // ========================================================================
    // Тесты: Retry-логика
    // ========================================================================

    @Nested
    @DisplayName("Retry при ошибках API")
    class RetryTests {

        @Test
        @DisplayName("Успех после одного HTTP 500")
        void testRetryAfterSingleFailure() throws Exception {
            AtomicInteger requestCount = new AtomicInteger(0);

            mockServer.createContext("/", exchange -> {
                int count = requestCount.incrementAndGet();
                if (count == 1) {
                    sendResponse(exchange, 500, "Internal Server Error");
                } else {
                    sendResponse(exchange, 200, "{\"data\":[42,84]}");
                }
            });

            RNProvider provider = createQuantumProvider(testSettings());
            assertTrue(provider.waitForInitialData(5000), "Должен загрузить после retry");
            assertEquals(2, requestCount.get(), "Должно быть 2 запроса (1 fail + 1 success)");
            assertEquals(42, provider.getNextRandomNumber().getAsInt());
        }

        @Test
        @DisplayName("Исчерпание retry → переключение в PSEUDO режим")
        void testAllRetriesExhausted() throws Exception {
            mockStatus(500, "Server Down");

            RNProvider provider = createQuantumProvider(testSettings());

            // Ждём пока retry-цикл завершится и сработает fallback
            long start = System.currentTimeMillis();
            while (provider.getMode() == RNProvider.Mode.QUANTUM && System.currentTimeMillis() - start < 5000) {
                Thread.sleep(50);
            }

            assertEquals(RNProvider.Mode.PSEUDO, provider.getMode(),
                    "Должен переключиться в PSEUDO после исчерпания retry");
            assertNull(provider.getLastError(),
                    "В PSEUDO режиме не должно быть ошибок, приложение продолжает работу");
            provider.shutdown(); // Останавливаем фоновый reconnect-monitor
        }

        @Test
        @DisplayName("Успех после двух HTTP 500 подряд")
        void testRetryAfterTwoFailures() throws Exception {
            AtomicInteger requestCount = new AtomicInteger(0);

            mockServer.createContext("/", exchange -> {
                int count = requestCount.incrementAndGet();
                if (count <= 2) {
                    sendResponse(exchange, 500, "Error");
                } else {
                    sendResponse(exchange, 200, "{\"data\":[999]}");
                }
            });

            RNProvider provider = createQuantumProvider(testSettings());
            assertTrue(provider.waitForInitialData(5000));
            assertEquals(3, requestCount.get(), "Должно быть 3 запроса (2 fail + 1 success)");
            assertNull(provider.getLastError(), "lastError должен быть сброшен после успеха");
        }
    }

    // ========================================================================
    // Тесты: Обработка ошибок в ответе
    // ========================================================================

    @Nested
    @DisplayName("Обработка ошибок API")
    class ErrorHandlingTests {

        @Test
        @DisplayName("Malformed JSON → fallback в PSEUDO")
        void testMalformedJson() throws Exception {
            mockSuccess("THIS IS NOT JSON {{{");

            RNProvider provider = createQuantumProvider(testSettings());

            // Ждём пока ретраи исчерпаются и сработает fallback
            long start = System.currentTimeMillis();
            while (provider.getMode() == RNProvider.Mode.QUANTUM && System.currentTimeMillis() - start < 5000) {
                Thread.sleep(50);
            }

            assertEquals(RNProvider.Mode.PSEUDO, provider.getMode(),
                    "При Malformed JSON должен уйти в PSEUDO");
            assertNull(provider.getLastError(), "В PSEUDO режиме ошибки сбрасываются");
            provider.shutdown(); // Останавливаем фоновый reconnect-monitor
        }

        @Test
        @DisplayName("API message error ({\"message\": \"...\"}) → fallback в PSEUDO")
        void testApiMessageError() throws Exception {
            mockSuccess("{\"message\":\"Rate limit exceeded\"}");

            RNProvider provider = createQuantumProvider(testSettings());

            long start = System.currentTimeMillis();
            while (provider.getMode() == RNProvider.Mode.QUANTUM && System.currentTimeMillis() - start < 5000) {
                Thread.sleep(50);
            }

            assertEquals(RNProvider.Mode.PSEUDO, provider.getMode(),
                    "При API error должен уйти в PSEUDO");
            assertNull(provider.getLastError());
            provider.shutdown(); // Останавливаем фоновый reconnect-monitor
        }

        @Test
        @DisplayName("Пустой data массив → пустой буфер, но без ошибки парсинга")
        void testEmptyDataArray() throws Exception {
            mockSuccess("{\"data\":[]}");

            RNProvider provider = createQuantumProvider(testSettings());
            assertTrue(provider.waitForInitialData(5000));
            // data пуст → queue пуст, но initialLoadComplete = true
            assertEquals(0, provider.getQueueSize());
        }

        @Test
        @DisplayName("Ответ без поля data и без message → fallback в PSEUDO")
        void testUnexpectedResponseFormat() throws Exception {
            mockSuccess("{\"something\":\"else\"}");

            RNProvider provider = createQuantumProvider(testSettings());

            long start = System.currentTimeMillis();
            while (provider.getMode() == RNProvider.Mode.QUANTUM && System.currentTimeMillis() - start < 5000) {
                Thread.sleep(50);
            }

            assertEquals(RNProvider.Mode.PSEUDO, provider.getMode(),
                    "При неожиданном ответе должен уйти в PSEUDO");
            assertNull(provider.getLastError());
            provider.shutdown(); // Останавливаем фоновый reconnect-monitor
        }

        // ========================================================================
        // Тесты: Буфер и getNextRandomNumber
        // ========================================================================

        @Nested
        @DisplayName("Буфер и getNextRandomNumber()")
        class BufferTests {

            @Test
            @DisplayName("Пустой буфер → возвращает OptionalInt.empty() без исключений")
            void testEmptyBufferReturnsEmpty() {
                mockSuccess("{\"data\":[]}");
                RNProvider provider = createQuantumProvider();
                // Загрузка запустилась, но data пуст → queue пуст

                // Теперь метод НЕ должен бросать исключение, он должен вернуть пустой Optional
                OptionalInt result = provider.getNextRandomNumber();

                assertTrue(result.isEmpty(), "Должен вернуть пустой OptionalInt, когда буфер пуст");
                assertEquals(RNProvider.Mode.QUANTUM, provider.getMode(),
                        "Не должен переключаться в PSEUDO, просто ждет подзагрузки");
            }

            @Test
            @DisplayName("maxApiRequests исчерпан → автоматический PSEUDO fallback при следующем запросе")
            void testMaxApiRequestsExhausted() throws Exception {
                // maxApiRequests = 1
                RNProvider.ProviderSettings settings = new RNProvider.ProviderSettings(
                        baseUrl, "test-key", "uint16",
                        2, 1, 1, // maxApiRequests = 1
                        2000, 2000, 0,
                        3, 1L, 10L
                );

                // Первый запрос — успех, загружает 2 числа
                mockSuccess("{\"data\":[10,20]}");
                RNProvider provider = createQuantumProvider(settings);
                assertTrue(provider.waitForInitialData(5000));

                // Потребляем оба числа (буфер пуст)
                provider.getNextRandomNumber();
                provider.getNextRandomNumber();

                // Запрашиваем 3-е число. Именно в этот момент
                // провайдер видит пустой буфер, проверяет лимит и включает PSEUDO
                int pseudoNum = provider.getNextRandomNumber()
                        .orElseThrow(() -> new AssertionError("Fallback должен вернуть число, а не пустой Optional"));

                // Теперь проверяем режим и возвращенное число
                assertEquals(RNProvider.Mode.PSEUDO, provider.getMode(),
                        "Должен переключиться в PSEUDO при запросе числа после исчерпания лимита");
                assertTrue(pseudoNum >= 0 && pseudoNum <= 65535,
                        "Должно вернуться псевдослучайное число, а не исключение. Получено: " + pseudoNum);
                provider.shutdown(); // Останавливаем фоновый reconnect-monitor
            }

            @Test
            @DisplayName("getNextRandomNumberInRange() делегирует в RandomNumberProcessor")
            void testGetNextRandomNumberInRange() throws Exception {
                mockSuccess("{\"data\":[0,32768,65535]}");
                RNProvider provider = createQuantumProvider(testSettings());
                assertTrue(provider.waitForInitialData(5000));

                // 0 → min диапазона, 65535 → max диапазона
                long result1 = provider.getNextRandomNumberInRange(0, 100);
                assertEquals(0, result1, "0 должен маппиться в начало диапазона");

                long result2 = provider.getNextRandomNumberInRange(0, 100);
                assertTrue(result2 >= 49 && result2 <= 51,
                        "32768 (~середина) должен маппиться в ~50, получено: " + result2);
            }
        }

        // ========================================================================
        // Тесты: Listener callbacks
        // ========================================================================

        @Nested
        @DisplayName("Listener callbacks")
        class ListenerTests {

            @Test
            @DisplayName("Успешная загрузка → started, rawData, completed")
            void testSuccessCallbacks() throws Exception {
                mockSuccess("{\"data\":[42]}");
                RecordingListener listener = new RecordingListener();

                // Важно: создаём без автозагрузки, регистрируем listener, затем запускаем
                RNProvider provider = new RNProvider(testSettings(), false, INSTANT_SLEEPER);
                provider.addDataLoadListener(listener);
                provider.setForcedPseudo(false); // Запускаем загрузку ПОСЛЕ регистрации listener

                assertTrue(listener.completedLatch.await(5, TimeUnit.SECONDS),
                        "onLoadingCompleted должен быть вызван");

                assertTrue(listener.events.contains("started"), "Должен вызвать onLoadingStarted");
                assertTrue(listener.events.contains("completed"), "Должен вызвать onLoadingCompleted");
                assertTrue(listener.events.contains("rawData"), "Должен вызвать onRawDataReceived");
                assertFalse(listener.rawDataList.isEmpty());
                assertTrue(listener.rawDataList.getFirst().contains("42"),
                        "rawData должен содержать ответ API");
            }

            @Test
            @DisplayName("Ошибка → started, error (несколько раз при retry)")
            void testErrorCallbacks() throws Exception {
                mockStatus(500, "Server Error");
                RecordingListener listener = new RecordingListener();

                RNProvider provider = new RNProvider(testSettings(), false, INSTANT_SLEEPER);
                provider.addDataLoadListener(listener);
                provider.setForcedPseudo(false); // Запускаем загрузку ПОСЛЕ регистрации listener

                // Ждём завершения retry-цикла
                long start = System.currentTimeMillis();
                while (provider.getLastError() == null && System.currentTimeMillis() - start < 5000) {
                    Thread.sleep(50);
                }

                assertTrue(listener.events.contains("started"), "Должен вызвать onLoadingStarted");
                assertTrue(listener.events.contains("error"), "Должен вызвать onError");
                assertTrue(listener.errors.size() >= 2,
                        "Должно быть несколько ошибок (retry), получено: " + listener.errors.size());
                provider.shutdown(); // Останавливаем фоновый reconnect-monitor
            }
        }

        // ========================================================================
        // Тесты: calculateBackoff
        // ========================================================================

        @Nested
        @DisplayName("calculateBackoff() — exponential backoff")
        class BackoffTests {

            @Test
            @DisplayName("Экспоненциальный рост: 1, 2, 4, 8, 10 (cap)")
            void testExponentialGrowthWithCap() {
                // initialBackoffMs=1, maxBackoffMs=10 (из testSettings)
                RNProvider provider = createProvider();

                assertEquals(1, provider.calculateBackoff(1));   // 1 * 2^0 = 1
                assertEquals(2, provider.calculateBackoff(2));   // 1 * 2^1 = 2
                assertEquals(4, provider.calculateBackoff(3));   // 1 * 2^2 = 4
                assertEquals(8, provider.calculateBackoff(4));   // 1 * 2^3 = 8
                assertEquals(10, provider.calculateBackoff(5));  // 1 * 2^4 = 16 → cap 10
                assertEquals(10, provider.calculateBackoff(10)); // Всегда cap
            }

            @Test
            @DisplayName("С продакшен-значениями: 1s, 2s, 4s, 8s, 16s, cap 30s")
            void testProductionBackoffValues() {
                RNProvider.ProviderSettings prodSettings = new RNProvider.ProviderSettings(
                        baseUrl, "key", "uint16", 5, 2, 100, 2000, 2000, 3,
                        5,       // maxRetries
                        1000L,   // initialBackoffMs
                        30000L   // maxBackoffMs
                );
                RNProvider provider = createProvider(prodSettings);

                assertEquals(1000, provider.calculateBackoff(1));
                assertEquals(2000, provider.calculateBackoff(2));
                assertEquals(4000, provider.calculateBackoff(3));
                assertEquals(8000, provider.calculateBackoff(4));
                assertEquals(16000, provider.calculateBackoff(5));
                assertEquals(30000, provider.calculateBackoff(6)); // cap
            }

            @ParameterizedTest
            @DisplayName("Backoff всегда положительный")
            @ValueSource(ints = {1, 2, 3, 5, 10, 20, 50})
            void testBackoffAlwaysPositive(int attempt) {
                RNProvider provider = createProvider();
                assertTrue(provider.calculateBackoff(attempt) > 0,
                        "Backoff для попытки " + attempt + " должен быть положительным");
            }
        }

        // ========================================================================
        // Тесты: Конфигурация API ключа
        // ========================================================================

        @Nested
        @DisplayName("Валидация API ключа")
        class ApiKeyValidationTests {

            @Test
            @DisplayName("null API ключ → мгновенный PSEUDO режим")
            void testNullApiKey() {
                RNProvider.ProviderSettings settings = new RNProvider.ProviderSettings(
                        baseUrl, null, "uint16", 5, 2, 100, 2000, 2000, 3, 3, 1L, 10L
                );
                RNProvider provider = new RNProvider(settings, true, INSTANT_SLEEPER);

                assertEquals(RNProvider.Mode.PSEUDO, provider.getMode(), "Должен перейти в PSEUDO");
                assertNull(provider.getLastError(), "Ошибка должна быть сброшена, приложение работает");
            }

            @Test
            @DisplayName("Пустой API ключ → мгновенный PSEUDO режим")
            void testEmptyApiKey() {
                RNProvider.ProviderSettings settings = new RNProvider.ProviderSettings(
                        baseUrl, "", "uint16", 5, 2, 100, 2000, 2000, 3, 3, 1L, 10L
                );
                RNProvider provider = new RNProvider(settings, true, INSTANT_SLEEPER);

                assertEquals(RNProvider.Mode.PSEUDO, provider.getMode());
                assertNull(provider.getLastError());
            }

            @Test
            @DisplayName("Placeholder YOUR_... → мгновенный PSEUDO режим")
            void testPlaceholderApiKey() {
                RNProvider.ProviderSettings settings = new RNProvider.ProviderSettings(
                        baseUrl, "YOUR_API_KEY_HERE", "uint16", 5, 2, 100, 2000, 2000, 3, 3, 1L, 10L
                );
                RNProvider provider = new RNProvider(settings, true, INSTANT_SLEEPER);

                assertEquals(RNProvider.Mode.PSEUDO, provider.getMode());
                assertNull(provider.getLastError());
            }

            @Test
            @DisplayName("Валидный ключ → lastError = null (до загрузки)")
            void testValidApiKey() {
                mockSuccess("{\"data\":[1]}");
                RNProvider provider = createProvider();
                // autoLoad=false, ключ валидный → ошибки нет
                assertNull(provider.getLastError());
            }
        }

        // ========================================================================
        // Тесты: waitForInitialData
        // ========================================================================

        @Nested
        @DisplayName("waitForInitialData()")
        class WaitForDataTests {

            @Test
            @DisplayName("Возвращает true при успешной загрузке")
            void testWaitReturnsTrue() throws Exception {
                mockSuccess("{\"data\":[1,2,3]}");
                RNProvider provider = createQuantumProvider(testSettings());

                assertTrue(provider.waitForInitialData(5000));
                assertTrue(provider.isInitialLoadComplete());
            }

            @Test
            @DisplayName("Возвращает false при timeout")
            void testWaitReturnsFalseOnTimeout() {
                // Сервер отвечает с задержкой 10 секунд → timeout
                mockServer.createContext("/", exchange -> {
                    try {
                        Thread.sleep(10000);
                    } catch (InterruptedException ignored) {
                    }
                    sendResponse(exchange, 200, "{\"data\":[1]}");
                });

                RNProvider provider = createQuantumProvider(testSettings());
                assertFalse(provider.waitForInitialData(500), "Должен вернуть false при timeout");
                provider.shutdown(); // Останавливаем фоновый reconnect-monitor
            }
        }

        // ========================================================================
        // Тесты: Sleeper вызывается при retry
        // ========================================================================

        @Nested
        @DisplayName("Sleeper injection")
        class SleeperTests {

            @Test
            @DisplayName("Sleeper вызывается с правильными значениями backoff")
            void testSleeperCalledWithBackoffValues() throws Exception {
                AtomicInteger requestCount = new AtomicInteger(0);
                List<Long> capturedSleeps = new CopyOnWriteArrayList<>();

                mockServer.createContext("/", exchange -> {
                    int count = requestCount.incrementAndGet();
                    if (count <= 2) {
                        sendResponse(exchange, 500, "Error");
                    } else {
                        sendResponse(exchange, 200, "{\"data\":[42]}");
                    }
                });

                RNProvider.Sleeper recordingSleeper = ms -> capturedSleeps.add(ms);

                RNProvider provider = new RNProvider(testSettings(), false, recordingSleeper);
                provider.setForcedPseudo(false); // Запускаем загрузку
                assertTrue(provider.waitForInitialData(5000));

                assertEquals(2, capturedSleeps.size(), "Должно быть 2 вызова sleep (2 retry)");
                assertEquals(1L, capturedSleeps.get(0), "Первый backoff = initialBackoffMs = 1");
                assertEquals(2L, capturedSleeps.get(1), "Второй backoff = 1 * 2^1 = 2");
            }
        }

        // ========================================================================
        // Тесты: shutdown
        // ========================================================================

        @Nested
        @DisplayName("shutdown()")
        class ShutdownTests {

            @Test
            @DisplayName("shutdown() не бросает исключений")
            void testShutdownDoesNotThrow() {
                RNProvider provider = createProvider();
                assertDoesNotThrow(provider::shutdown);
            }
        }

        // ========================================================================
        // Тесты: Thread-safety (smoke)
        // ========================================================================

        @Nested
        @DisplayName("Thread safety (smoke test)")
        class ThreadSafetyTests {

            @Test
            @DisplayName("Параллельные getNextRandomNumber() не крашат провайдер")
            void testConcurrentAccess() throws Exception {
                // Загружаем 100 чисел
                StringBuilder json = new StringBuilder("{\"data\":[");
                for (int i = 0; i < 100; i++) {
                    if (i > 0) json.append(",");
                    json.append(i);
                }
                json.append("]}");
                mockSuccess(json.toString());

                RNProvider provider = createQuantumProvider(testSettings());
                assertTrue(provider.waitForInitialData(5000));

                int threadCount = 10;
                CountDownLatch startLatch = new CountDownLatch(1);
                CountDownLatch doneLatch = new CountDownLatch(threadCount);
                List<Throwable> errors = new CopyOnWriteArrayList<>();

                for (int t = 0; t < threadCount; t++) {
                    new Thread(() -> {
                        try {
                            startLatch.await();
                            for (int i = 0; i < 10; i++) {
                                try {
                                    provider.getNextRandomNumber();
                                } catch (NoSuchElementException ignored) {
                                    // Ожидаемо — буфер может опустеть
                                }
                            }
                        } catch (Throwable e) {
                            errors.add(e);
                        } finally {
                            doneLatch.countDown();
                        }
                    }).start();
                }

                startLatch.countDown(); // Запускаем все потоки одновременно
                assertTrue(doneLatch.await(10, TimeUnit.SECONDS));
                assertTrue(errors.isEmpty(),
                        "Не должно быть неожиданных ошибок: " + errors);
            }
        }
    }
}
