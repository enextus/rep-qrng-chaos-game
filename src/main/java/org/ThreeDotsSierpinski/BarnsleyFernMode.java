package org.ThreeDotsSierpinski;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

/**
 * Режим визуализации: Barnsley Fern.
 * <p>
 * Папоротник Барнсли строится как Iterated Function System (IFS):
 * каждое случайное число выбирает одно из четырёх аффинных преобразований.
 * Из последовательности случайных решений постепенно возникает узнаваемая
 * фрактальная форма папоротника.
 */
public class BarnsleyFernMode implements VisualizationMode {

    private static final String ID = "barnsley-fern";
    private static final String NAME = "Barnsley Fern";
    private static final String DESCRIPTION =
            "Случайные числа выбирают affine transformations.\n"
          + "Из хаоса постепенно вырастает фрактальный папоротник.";
    private static final String ICON = "🌿";

    private static final int ITERATIONS_PER_STEP = 250;
    private static final int PROBABILITY_SCALE = 10_000;

    private static final int STEM_THRESHOLD = 100;          // 1%
    private static final int SUCCESSIVE_THRESHOLD = 8_600;  // 85%
    private static final int LEFT_LEAFLET_THRESHOLD = 9_300; // 7%
    // remaining 7% -> right leaflet

    private static final double INITIAL_X = 0.0;
    private static final double INITIAL_Y = 0.0;

    private static final double FERN_MIN_X = -2.1820;
    private static final double FERN_MAX_X = 2.6558;
    private static final double FERN_MIN_Y = 0.0;
    private static final double FERN_MAX_Y = 9.9983;

    private static final double SCREEN_MARGIN_RATIO = 0.04;
    private static final int MIN_SCREEN_MARGIN = 12;
    private static final int MIN_RENDER_SIZE = 1;
    private static final int MIN_DOT_SIZE = 1;
    private static final int MAX_DOT_SIZE = 3;

    private static final int BLACK_RGB = 0xFF000000;

    private static final float HUE_BASE = 0.28f;
    private static final float HUE_RANGE = 0.12f;
    private static final float SATURATION_BASE = 0.68f;
    private static final float SATURATION_RANGE = 0.24f;
    private static final float BRIGHTNESS_BASE = 0.70f;
    private static final float BRIGHTNESS_RANGE = 0.28f;

    private int width;
    private int height;
    private double currentX = INITIAL_X;
    private double currentY = INITIAL_Y;
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
    public boolean usesRecolorAnimation() {
        return false;
    }

    @Override
    public boolean usesDarkBackground() {
        return true;
    }

    @Override
    public void initialize(BufferedImage canvas, int width, int height) {
        this.width = width;
        this.height = height;
        this.currentX = INITIAL_X;
        this.currentY = INITIAL_Y;
        this.pointCount = 0;
        this.randomNumbersUsed = 0;

        fillBackground(canvas);
    }

    @Override
    public List<Point> step(RNProvider provider, BufferedImage canvas, int dotSize) {
        var newPoints = new ArrayList<Point>();

        Graphics2D g = canvas.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int renderDotSize = Math.clamp(dotSize, MIN_DOT_SIZE, MAX_DOT_SIZE);

        for (int i = 0; i < ITERATIONS_PER_STEP; i++) {
            OptionalInt randomValue = provider.getNextRandomNumber();
            if (randomValue.isEmpty()) {
                break;
            }

            applyTransform(Math.floorMod(randomValue.getAsInt(), PROBABILITY_SCALE));
            randomNumbersUsed++;
            pointCount++;

            Point screenPoint = mapFernPointToScreen(currentX, currentY);
            drawFernPoint(g, screenPoint, renderDotSize);
            newPoints.add(screenPoint);
        }

        g.dispose();
        return newPoints;
    }

    private void applyTransform(int selector) {
        double nextX;
        double nextY;

        if (selector < STEM_THRESHOLD) {
            nextX = 0.0;
            nextY = 0.16 * currentY;
        } else if (selector < SUCCESSIVE_THRESHOLD) {
            nextX = 0.85 * currentX + 0.04 * currentY;
            nextY = -0.04 * currentX + 0.85 * currentY + 1.6;
        } else if (selector < LEFT_LEAFLET_THRESHOLD) {
            nextX = 0.20 * currentX - 0.26 * currentY;
            nextY = 0.23 * currentX + 0.22 * currentY + 1.6;
        } else {
            nextX = -0.15 * currentX + 0.28 * currentY;
            nextY = 0.26 * currentX + 0.24 * currentY + 0.44;
        }

        currentX = nextX;
        currentY = nextY;
    }

    private Point mapFernPointToScreen(double fernX, double fernY) {
        int margin = calculateMargin();

        int renderWidth = Math.max(MIN_RENDER_SIZE, width - 2 * margin);
        int renderHeight = Math.max(MIN_RENDER_SIZE, height - 2 * margin);

        double normalizedX = (fernX - FERN_MIN_X) / (FERN_MAX_X - FERN_MIN_X);
        double normalizedY = (fernY - FERN_MIN_Y) / (FERN_MAX_Y - FERN_MIN_Y);

        int screenX = margin + (int) Math.round(normalizedX * renderWidth);
        int screenY = height - margin - (int) Math.round(normalizedY * renderHeight);

        return new Point(
                Math.clamp(screenX, 0, width - 1),
                Math.clamp(screenY, 0, height - 1)
        );
    }

    private int calculateMargin() {
        int shortestSide = Math.min(width, height);
        return Math.max(MIN_SCREEN_MARGIN, (int) Math.round(shortestSide * SCREEN_MARGIN_RATIO));
    }

    private void drawFernPoint(Graphics2D g, Point point, int dotSize) {
        float depth = (float) Math.clamp(currentY / FERN_MAX_Y, 0.0, 1.0);
        float hue = HUE_BASE + HUE_RANGE * depth;
        float saturation = SATURATION_BASE + SATURATION_RANGE * depth;
        float brightness = BRIGHTNESS_BASE + BRIGHTNESS_RANGE * depth;

        g.setColor(Color.getHSBColor(hue, saturation, brightness));
        g.fillOval(point.x, point.y, dotSize, dotSize);
    }

    private void fillBackground(BufferedImage canvas) {
        int[] pixels = new int[width * height];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = BLACK_RGB;
        }
        canvas.setRGB(0, 0, width, height, pixels, 0, width);
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
