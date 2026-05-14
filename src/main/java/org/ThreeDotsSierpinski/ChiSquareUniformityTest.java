package org.ThreeDotsSierpinski;

import java.util.List;

/**
 * Тест хи-квадрат для проверки равномерности распределения.
 */
public class ChiSquareUniformityTest implements RandomnessTest {

    private static final int NUM_BINS = 16;

    private static final long MIN_RANGE = 0;
    private static final long MAX_RANGE = 65_535;

    private static final int MIN_REQUIRED_NUMBERS = 10;

    private static final double ALPHA_01 = 0.01;
    private static final double ALPHA_05 = 0.05;

    /**
     * Critical values for Chi-Square distribution with df = NUM_BINS - 1 = 15.
     */
    private static final double CRITICAL_VALUE_ALPHA_01 = 30.578;
    private static final double CRITICAL_VALUE_ALPHA_05 = 24.996;
    private static final double CRITICAL_VALUE_DEFAULT = 22.307;

    private static final double STRONG_QUALITY_THRESHOLD_FACTOR = 0.6;

    private static final String ERROR_MIN_NUMBERS_REQUIRED =
            "Требуется минимум %d чисел";

    private static final String STAT_FORMAT =
            "\u03c7\u00b2=%.2f (crit=%.2f)";

    private static double getCriticalValue(double alpha) {
        if (alpha <= ALPHA_01) {
            return CRITICAL_VALUE_ALPHA_01;
        }

        if (alpha <= ALPHA_05) {
            return CRITICAL_VALUE_ALPHA_05;
        }

        return CRITICAL_VALUE_DEFAULT;
    }

    @Override
    public TestResult testWithDetails(List<Long> numbers, double alpha) {
        if (numbers == null || numbers.size() < MIN_REQUIRED_NUMBERS) {
            throw new IllegalArgumentException(
                    String.format(ERROR_MIN_NUMBERS_REQUIRED, MIN_REQUIRED_NUMBERS)
            );
        }

        int[] bins = new int[NUM_BINS];
        long binSize = (MAX_RANGE - MIN_RANGE + 1) / NUM_BINS;

        for (long number : numbers) {
            int binIndex = (int) Math.min(
                    (number - MIN_RANGE) / binSize,
                    NUM_BINS - 1
            );

            bins[binIndex]++;
        }

        double expectedCount = (double) numbers.size() / NUM_BINS;
        double chiSquare = 0.0;

        for (int count : bins) {
            chiSquare += Math.pow(count - expectedCount, 2) / expectedCount;
        }

        double critical = getCriticalValue(alpha);

        var quality = chiSquare < critical * STRONG_QUALITY_THRESHOLD_FACTOR
                ? TestResult.Quality.STRONG
                : chiSquare < critical
                  ? TestResult.Quality.MARGINAL
                  : TestResult.Quality.FAIL;

        String stat = String.format(STAT_FORMAT, chiSquare, critical);

        return new TestResult(
                getTestName(),
                quality != TestResult.Quality.FAIL,
                stat,
                quality
        );
    }

    @Override
    public String getTestName() {
        return "Хи-квадрат (Chi-Square)";
    }

}
