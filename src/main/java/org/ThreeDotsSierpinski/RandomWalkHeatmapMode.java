package org.ThreeDotsSierpinski;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.OptionalInt;

/**
 * Режим визуализации: Random Walk Heatmap.
 * <p>
 * Несколько блуждающих частиц стартуют из центра. Каждое случайное число
 * выбирает направление движения. Чем чаще посещён пиксель, тем ярче и теплее
 * становится его цвет.
 */
public class RandomWalkHeatmapMode implements VisualizationMode {

    private static final String ID = "random-walk-heatmap";
    private static final String NAME = "Random Walk Heatmap";
    private static final String DESCRIPTION = "Случайное блуждание строит тепловую карту посещений.\n"
            + "Чем чаще область посещается, тем ярче она светится.";
    private static final String ICON = "🔥";

    private static final int WALKER_COUNT = 32;
    private static final int STEPS_PER_WALKER_PER_TICK = 32;
    private static final int HEAT_SATURATION_VISITS = 64;
    private static final int MIN_CANVAS_SIZE = 1;
    private static final int CENTER_DIVISOR = 2;
    private static final int INITIAL_COUNTER_VALUE = 0;
    private static final int MIN_DRAW_SIZE = 1;

    private static final int BACKGROUND_RGB = 0xFF000000;

    private static final float HOT_HUE = 0.0f;
    private static final float COLD_HUE = 0.66f;
    private static final float HEAT_SATURATION = 1.0f;
    private static final float MIN_BRIGHTNESS = 0.12f;
    private static final float BRIGHTNESS_RANGE = 0.88f;

    /**
     * 8 направлений: четыре ортогональных + четыре диагональных.
     */
    private static final int[][] WALK_DIRECTIONS = {
            {0, -1},
            {1, -1},
            {1, 0},
            {1, 1},
            {0, 1},
            {-1, 1},
            {-1, 0},
            {-1, -1}
    };

    private int width;
    private int height;
    private int[] visits;
    private int[] walkerX;
    private int[] walkerY;
    private int pointCount = INITIAL_COUNTER_VALUE;
    private int randomNumbersUsed = INITIAL_COUNTER_VALUE;

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
    public boolean usesRecolorAnimation() {
        return false;
    }

    @Override
    public boolean usesDarkBackground() {
        return true;
    }

    @Override
    public void initialize(BufferedImage canvas, int width, int height) {
        this.width = Math.max(MIN_CANVAS_SIZE, width);
        this.height = Math.max(MIN_CANVAS_SIZE, height);
        this.visits = new int[this.width * this.height];
        this.walkerX = new int[WALKER_COUNT];
        this.walkerY = new int[WALKER_COUNT];
        this.pointCount = INITIAL_COUNTER_VALUE;
        this.randomNumbersUsed = INITIAL_COUNTER_VALUE;

        int centerX = this.width / CENTER_DIVISOR;
        int centerY = this.height / CENTER_DIVISOR;

        for (int i = 0; i < WALKER_COUNT; i++) {
            walkerX[i] = centerX;
            walkerY[i] = centerY;
        }

        fillBackground(canvas);
        markVisit(canvas, centerX, centerY, Math.max(MIN_DRAW_SIZE, Config.getInt("dot.size")));
    }

    @Override
    public List<Point> step(RNProvider provider, BufferedImage canvas, int dotSize) {
        if (visits == null || walkerX == null || walkerY == null) {
            initialize(canvas, canvas.getWidth(), canvas.getHeight());
        }

        int drawSize = Math.max(MIN_DRAW_SIZE, dotSize);

        for (int walkerIndex = 0; walkerIndex < WALKER_COUNT; walkerIndex++) {
            for (int step = 0; step < STEPS_PER_WALKER_PER_TICK; step++) {
                OptionalInt randomDirection = provider.getNextRandomNumber();
                if (randomDirection.isEmpty()) {
                    return List.of();
                }

                int directionIndex = Math.floorMod(randomDirection.getAsInt(), WALK_DIRECTIONS.length);
                randomNumbersUsed++;

                walkerX[walkerIndex] = clamp(
                        walkerX[walkerIndex] + WALK_DIRECTIONS[directionIndex][0],
                        0,
                        width - 1
                );

                walkerY[walkerIndex] = clamp(
                        walkerY[walkerIndex] + WALK_DIRECTIONS[directionIndex][1],
                        0,
                        height - 1
                );

                markVisit(canvas, walkerX[walkerIndex], walkerY[walkerIndex], drawSize);
                pointCount++;
            }
        }

        return List.of();
    }

    @Override
    public void redraw(BufferedImage canvas, int width, int height, int dotSize) {
        if (visits == null || this.width != width || this.height != height) {
            initialize(canvas, width, height);
            return;
        }

        fillBackground(canvas);

        int drawSize = Math.max(MIN_DRAW_SIZE, dotSize);
        Graphics2D g = canvas.createGraphics();
        try {
            for (int y = 0; y < this.height; y++) {
                for (int x = 0; x < this.width; x++) {
                    int visitCount = visits[toIndex(x, y)];
                    if (visitCount > 0) {
                        g.setColor(getHeatColor(visitCount));
                        drawHeatPoint(g, x, y, drawSize);
                    }
                }
            }
        } finally {
            g.dispose();
        }
    }

    private void markVisit(BufferedImage canvas, int x, int y, int drawSize) {
        visits[toIndex(x, y)]++;

        Graphics2D g = canvas.createGraphics();
        try {
            g.setColor(getHeatColor(visits[toIndex(x, y)]));
            drawHeatPoint(g, x, y, drawSize);
        } finally {
            g.dispose();
        }
    }

    private void drawHeatPoint(Graphics2D g, int x, int y, int drawSize) {
        int halfSize = drawSize / CENTER_DIVISOR;
        int drawX = clamp(x - halfSize, 0, width - 1);
        int drawY = clamp(y - halfSize, 0, height - 1);
        int drawWidth = Math.min(drawSize, width - drawX);
        int drawHeight = Math.min(drawSize, height - drawY);

        g.fillRect(drawX, drawY, drawWidth, drawHeight);
    }

    private Color getHeatColor(int visitCount) {
        double normalizedHeat = Math.min(
                1.0,
                Math.log1p(visitCount) / Math.log1p(HEAT_SATURATION_VISITS)
        );

        float hue = (float) (COLD_HUE - (COLD_HUE - HOT_HUE) * normalizedHeat);
        float brightness = (float) (MIN_BRIGHTNESS + BRIGHTNESS_RANGE * normalizedHeat);

        return Color.getHSBColor(hue, HEAT_SATURATION, brightness);
    }

    private void fillBackground(BufferedImage canvas) {
        int[] pixels = new int[canvas.getWidth() * canvas.getHeight()];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = BACKGROUND_RGB;
        }
        canvas.setRGB(0, 0, canvas.getWidth(), canvas.getHeight(), pixels, 0, canvas.getWidth());
    }

    private int toIndex(int x, int y) {
        return y * width + x;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
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
