package org.ThreeDotsSierpinski;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.logging.Level;

/**
 * Класс для загрузки и предоставления конфигурационных параметров.
 * <p>
 * Приоритет загрузки значений (от высшего к низшему):
 * <ol>
 *   <li>Переменные окружения (например, QRNG_API_KEY)</li>
 *   <li>Файл .env в корне проекта</li>
 *   <li>Файл config.properties из classpath</li>
 * </ol>
 * <p>
 * Конвенция именования переменных окружения:
 * Ключ из properties преобразуется в UPPER_SNAKE_CASE с префиксом QRNG_.
 * Например: api.key → QRNG_API_KEY, api.url → QRNG_API_URL
 */
public class Config {

    private static final String ENV_PREFIX = "QRNG_";
    private static final Properties configProperties = new Properties();
    private static final Properties envFileProperties = new Properties();

    static {
        try {
            loadConfigProperties();
            loadEnvFile();
        } catch (Exception ex) {
            // Предотвращаем ExceptionInInitializerError.
            // Если конфиг не загрузился, класс не умирает, методы просто будут возвращать null.
            System.err.println("FATAL: Failed to initialize Config. Error: " + ex.getMessage());
        }
    }

    /**
     * Загружает config.properties из classpath.
     */
    private static void loadConfigProperties() {
        try (InputStream input = Config.class.getClassLoader().getResourceAsStream("config.properties")) {
            if (input == null) {
                throw new RuntimeException("Файл конфигурации config.properties не найден в classpath.");
            }
            configProperties.load(input);
        } catch (IOException ex) {
            throw new RuntimeException("Не удалось загрузить файл конфигурации config.properties.", ex);
        }
    }

    /**
     * Загружает .env файл из нескольких возможных мест.
     * <p>
     * Приоритет:
     * 1. .env из текущей рабочей директории
     * 2. .env рядом с запущенным JAR-файлом
     * 3. .env из домашней директории пользователя:
     * ~/.rep-qrng-chaos-game/.env
     */
    private static void loadEnvFile() {
        Path currentDirEnv = Paths.get(".env").toAbsolutePath().normalize();

        Path jarDir = getApplicationDirectory();
        Path jarDirEnv = jarDir == null
                ? null
                : jarDir.resolve(".env").toAbsolutePath().normalize();

        Path userHomeEnv = Paths.get(
                System.getProperty("user.home"),
                ".rep-qrng-chaos-game",
                ".env"
        ).toAbsolutePath().normalize();

        Path[] candidates = jarDirEnv == null
                ? new Path[]{currentDirEnv, userHomeEnv}
                : new Path[]{currentDirEnv, jarDirEnv, userHomeEnv};

        for (Path envPath : candidates) {
            if (Files.isRegularFile(envPath)) {
                readEnvFile(envPath);
                return;
            }
        }
    }

    /**
     * Читает конкретный .env файл.
     * Формат файла: KEY=VALUE.
     */
    private static void readEnvFile(Path envPath) {
        try (BufferedReader reader = Files.newBufferedReader(envPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();

                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                int separatorIndex = line.indexOf('=');
                if (separatorIndex > 0) {
                    String key = line.substring(0, separatorIndex).trim();
                    String value = line.substring(separatorIndex + 1).trim();

                    if (value.length() >= 2
                            && ((value.startsWith("\"") && value.endsWith("\""))
                            || (value.startsWith("'") && value.endsWith("'")))) {
                        value = value.substring(1, value.length() - 1);
                    }

                    envFileProperties.setProperty(key, value);
                }
            }
        } catch (IOException ex) {
            System.err.println("Warning: Could not read .env file "
                    + envPath + ": " + ex.getMessage());
        }
    }

    /**
     * Возвращает директорию, из которой запущено приложение.
     * <p>
     * Если приложение запущено из JAR:
     * C:/tools/qrng/app.jar -> C:/tools/qrng
     * <p>
     * Если приложение запущено из IDE:
     * target/classes -> target/classes
     */
    private static Path getApplicationDirectory() {
        try {
            Path location = Paths.get(
                    Config.class
                            .getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .toURI()
            ).toAbsolutePath().normalize();

            if (Files.isRegularFile(location)) {
                return location.getParent();
            }

            if (Files.isDirectory(location)) {
                return location;
            }

            return null;
        } catch (URISyntaxException | SecurityException ex) {
            return null;
        }
    }

    /**
     * Преобразует ключ из dot.notation в UPPER_SNAKE_CASE с префиксом QRNG_.
     * Например: api.key → QRNG_API_KEY
     *
     * @param propertyKey Ключ в формате dot.notation
     * @return Ключ в формате QRNG_UPPER_SNAKE_CASE
     */
    static String toEnvVarName(String propertyKey) {
        return ENV_PREFIX + propertyKey.replace('.', '_').toUpperCase();
    }

    /**
     * Получает строковое значение параметра с учётом приоритетов:
     * 1. Переменная окружения (QRNG_UPPER_SNAKE_CASE)
     * 2. Значение из .env файла (QRNG_UPPER_SNAKE_CASE)
     * 3. Значение из config.properties (dot.notation)
     *
     * @param key Ключ параметра в формате dot.notation
     * @return Значение параметра или null, если не найдено
     */
    public static String getString(String key) {
        String envVarName = toEnvVarName(key);

        // Приоритет 1: Переменная окружения
        String envValue = System.getenv(envVarName);
        if (envValue != null && !envValue.isEmpty()) {
            return envValue;
        }

        // Приоритет 2: .env файл
        String envFileValue = envFileProperties.getProperty(envVarName);
        if (envFileValue != null && !envFileValue.isEmpty()) {
            return envFileValue;
        }

        // Приоритет 3: config.properties
        return configProperties.getProperty(key);
    }

    /**
     * Получает целочисленное значение параметра.
     *
     * @param key Ключ параметра
     * @return Целочисленное значение параметра
     * @throws NumberFormatException если значение не является числом
     * @throws RuntimeException      если параметр не найден
     */
    public static int getInt(String key) {
        String value = getString(key);
        if (value == null) {
            throw new RuntimeException("Параметр не найден: " + key);
        }
        return Integer.parseInt(value);
    }

    /**
     * Получает длинное (long) значение параметра.
     *
     * @param key Ключ параметра
     * @return Длинное значение параметра
     * @throws NumberFormatException если значение не является числом
     * @throws RuntimeException      если параметр не найден
     */
    public static long getLong(String key) {
        String value = getString(key);
        if (value == null) {
            throw new RuntimeException("Параметр не найден: " + key);
        }
        return Long.parseLong(value);
    }

    /**
     * Получает значение параметра в виде double.
     *
     * @param key Ключ параметра
     * @return Значение параметра как double
     * @throws NumberFormatException если значение не является числом
     * @throws RuntimeException      если параметр не найден
     */
    public static double getDouble(String key) {
        String value = getString(key);
        if (value == null) {
            throw new RuntimeException("Параметр не найден: " + key);
        }
        return Double.parseDouble(value);
    }

    /**
     * Получает уровень логирования.
     *
     * @return Уровень логирования
     */
    public static Level getLogLevel() {
        String value = getString("log.level");
        return Level.parse(value != null ? value : "INFO");
    }

}
