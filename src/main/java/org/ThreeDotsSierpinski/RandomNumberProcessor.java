package org.ThreeDotsSierpinski;

import java.util.ArrayList;
import java.util.List;

/**
 * Класс для обработки случайных чисел и генерации чисел в заданном диапазоне.
 * Поддерживает работу с данными от ANU QRNG API:
 * - uint8: 0-255
 * - uint16: 0-65535
 * - hex16: шестнадцатеричные строки
 */
public class RandomNumberProcessor {

    // Максимальное значение для uint16 (используется по умолчанию)
    private static final int MAX_UINT16 = 65535;

    /**
     * Преобразует HEX-строку в список чисел в диапазоне [0, 65535].
     * Используется для обратной совместимости с hex16 форматом.
     *
     * @param hexData HEX-строка от API.
     * @return Список чисел (16-битные значения).
     * @throws IllegalArgumentException Если HEX-строка некорректна.
     */
    public List<Integer> processHexToNumbers(String hexData) {
        byte[] bytes = hexStringToByteArray(hexData);
        List<Integer> numbers = new ArrayList<>();

        for (int i = 0; i < bytes.length - 1; i += 2) {
            int high = bytes[i] & 0xFF;
            int low = bytes[i + 1] & 0xFF;
            int combined = (high << 8) | low; // 0–65535
            numbers.add(combined);
        }

        return numbers;
    }

    /**
     * Генерирует число в заданном диапазоне [min, max] из случайного числа.
     * Автоматически определяет максимальное значение входного числа
     * на основе его величины (uint8 или uint16).
     *
     * @param number Случайное число от API (0-255 или 0-65535).
     * @param min Минимальное значение диапазона.
     * @param max Максимальное значение диапазона.
     * @return Число в диапазоне [min, max].
     */
    public long generateNumberInRange(int number, long min, long max) {
        return generateNumberInRange(number, min, max, MAX_UINT16);
    }

    /**
     * Генерирует число в заданном диапазоне с явным указанием максимального значения.
     * Использует floor-маппинг для равномерного распределения по всему диапазону [min, max],
     * включая граничные значения. Каждое выходное значение получает одинаковое количество
     * входных значений (в пределах ±1).
     *
     * @param number Случайное число от API.
     * @param min Минимальное значение диапазона.
     * @param max Максимальное значение диапазона.
     * @param sourceMax Максимальное значение источника (255 для uint8, 65535 для uint16).
     * @return Число в диапазоне [min, max].
     */
    public long generateNumberInRange(int number, long min, long max, int sourceMax) {
        double normalized = (double) number / (sourceMax + 1.0); // [0.0, 1.0)
        long outputRange = max - min + 1;
        long result = min + (long) (normalized * outputRange);
        return Math.min(result, max); // Защита от выхода за границу из-за погрешности double
    }

    /**
     * Преобразует HEX-строку в массив байтов.
     *
     * @param s HEX-строка.
     * @return Массив байтов.
     * @throws IllegalArgumentException Если строка некорректна.
     */
    private byte[] hexStringToByteArray(String s) {
        int len = s.length();
        if (len % 2 != 0) {
            throw new IllegalArgumentException("Invalid HEX string length.");
        }
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            int high = Character.digit(s.charAt(i), 16);
            int low = Character.digit(s.charAt(i + 1), 16);
            if (high == -1 || low == -1) {
                throw new IllegalArgumentException("Invalid character in HEX string.");
            }
            data[i / 2] = (byte) ((high << 4) + low);
        }
        return data;
    }

}
