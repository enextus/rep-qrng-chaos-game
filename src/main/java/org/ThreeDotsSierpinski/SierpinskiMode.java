package org.ThreeDotsSierpinski;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Режим визуализации: треугольник Серпинского (Chaos Game).
 * Классический алгоритм:
 * 1. Начинаем с центра треугольника
 * 2. Случайное число определяет одну из трёх вершин
 * 3. Перемещаемся на половину расстояния к вершине
 * 4. Ставим точку
 * Из чистого хаоса рождается фрактальная структура.
 */
public class SierpinskiMode implements VisualizationMode {

    private static final String CONFIG_DOTS_PER_UPDATE = "dots.per.update";

    private static final String ID = "Sierpinski";
    private static final String NAME = "Sierpinski Triangle";
    private static final String DESCRIPTION =
            "Фрактал из хаоса: случайные числа определяют вершину,\n"
                    + "точка прыгает на полпути — и возникает треугольник Серпинского.";
    private static final String ICON = "△";

    private static final String ERROR_CANVAS_NULL = "Canvas cannot be null";
    private static final String ERROR_PROVIDER_NULL = "Provider cannot be null";
    private static final String ERROR_INVALID_CANVAS_SIZE = "Canvas size must be positive";

    private static final int CENTER_DIVISOR = 2;
    private static final int MIN_DOT_SIZE = 1;
    private static final int MIN_DOTS_PER_STEP = 1;

    private static final Color NEW_POINT_COLOR = Color.RED;

    private static final int DOTS_PER_STEP = Math.max(
            MIN_DOTS_PER_STEP,
            Config.getInt(CONFIG_DOTS_PER_UPDATE)
    );

    private SierpinskiAlgorithm algorithm;
    private Point currentPoint;
    private int pointCount = 0;
    private int randomNumbersUsed = 0;

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return DESCRIPTION;
    }

    @Override
    public String getIcon() {
        return ICON;
    }

    @Override
    public void initialize(BufferedImage canvas, int width, int height) {
        Objects.requireNonNull(canvas, ERROR_CANVAS_NULL);

        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(ERROR_INVALID_CANVAS_SIZE);
        }

        algorithm = new SierpinskiAlgorithm(width, height);
        currentPoint = new Point(width / CENTER_DIVISOR, height / CENTER_DIVISOR);
        pointCount = 0;
        randomNumbersUsed = 0;
    }

    @Override
    public List<Point> step(RNProvider provider, BufferedImage canvas, int dotSize) {
        Objects.requireNonNull(provider, ERROR_PROVIDER_NULL);
        Objects.requireNonNull(canvas, ERROR_CANVAS_NULL);

        ensureInitialized(canvas);

        int safeDotSize = Math.max(MIN_DOT_SIZE, dotSize);
        var newPoints = new ArrayList<Point>(DOTS_PER_STEP);

        Graphics2D g2d = canvas.createGraphics();

        try {
            g2d.setColor(NEW_POINT_COLOR);

            for (int i = 0; i < DOTS_PER_STEP; i++) {
                OptionalInt randomOpt = provider.getNextRandomNumber();

                if (randomOpt.isEmpty()) {
                    break;
                }

                long randomValue = randomOpt.getAsInt();
                randomNumbersUsed++;

                currentPoint = algorithm.calculateNewDotPosition(currentPoint, randomValue);

                g2d.fillRect(
                        currentPoint.x,
                        currentPoint.y,
                        safeDotSize,
                        safeDotSize
                );

                newPoints.add(new Point(currentPoint));
                pointCount++;
            }
        } finally {
            g2d.dispose();
        }

        return newPoints;
    }

    private void ensureInitialized(BufferedImage canvas) {
        if (algorithm == null || currentPoint == null) {
            initialize(canvas, canvas.getWidth(), canvas.getHeight());
        }
    }

    @Override
    public int getPointCount() {
        return pointCount;
    }

    @Override
    public int getRandomNumbersUsed() {
        return randomNumbersUsed;
    }
}