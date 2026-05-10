package org.ThreeDotsSierpinski;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

/**
 * Режим визуализации: доска Гальтона / биномиальное распределение.
 * <p>
 * Каждый шарик проходит через несколько уровней. На каждом уровне одно
 * случайное число решает направление: влево или вправо. Внизу постепенно
 * накапливается гистограмма, которая при честном источнике случайности
 * стремится к колоколообразной биномиальной форме.
 */
public class GaltonBoardMode implements VisualizationMode {

    private static final String ID = "galton-board";
    private static final String NAME = "Galton Board";
    private static final String DESCRIPTION =
            "Шарики падают через уровни случайных развилок.\n"
          + "Из бинарного хаоса постепенно возникает биномиальное распределение.";
    private static final String ICON = "📊";

    private static final int LEVELS = 32;
    private static final int BUCKETS = LEVELS + 1;
    private static final int BALLS_PER_STEP = 25;

    private static final int BACKGROUND_RGB = 0xFF000000;
    private static final Color BACKGROUND_COLOR = Color.BLACK;
    private static final Color PEG_COLOR = new Color(125, 145, 175);
    private static final Color PEG_HIGHLIGHT_COLOR = new Color(190, 210, 240);
    private static final Color BAR_BORDER_COLOR = new Color(18, 26, 36);
    private static final Color EXPECTED_CURVE_COLOR = new Color(255, 225, 90);
    private static final Color TEXT_COLOR = new Color(190, 210, 255);
    private static final Color SUBTLE_TEXT_COLOR = new Color(135, 150, 180);

    private static final int OUTER_MARGIN = 42;
    private static final int TOP_MARGIN = 95;
    private static final int BOARD_BOTTOM_GAP = 210;
    private static final int HISTOGRAM_BOTTOM_MARGIN = 46;
    private static final int HISTOGRAM_TOP_GAP = 36;
    private static final int PEG_RADIUS = 3;
    private static final int BAR_GAP = 2;
    private static final int MIN_BAR_WIDTH = 4;
    private static final int DISTRIBUTION_LABEL_X = 18;
    private static final int DISTRIBUTION_LABEL_Y = 150;
    private static final int SMALL_FONT_SIZE = 12;
    private static final int LEGEND_FONT_SIZE = 11;

    private int width;
    private int height;
    private int[] buckets;
    private int pointCount;
    private int randomNumbersUsed;

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
        this.buckets = new int[BUCKETS];
        this.pointCount = 0;
        this.randomNumbersUsed = 0;

        clear(canvas);
        render(canvas);
    }

    @Override
    public List<Point> step(RNProvider provider, BufferedImage canvas, int dotSize) {
        List<Point> newPoints = new ArrayList<>();

        for (int ball = 0; ball < BALLS_PER_STEP; ball++) {
            int bucketIndex = 0;

            for (int level = 0; level < LEVELS; level++) {
                OptionalInt randomValue = provider.getNextRandomNumber();
                if (randomValue.isEmpty()) {
                    render(canvas);
                    return newPoints;
                }

                randomNumbersUsed++;

                if ((randomValue.getAsInt() & 1) != 0) {
                    bucketIndex++;
                }
            }

            buckets[bucketIndex]++;
            pointCount++;
            newPoints.add(new Point(bucketIndex, buckets[bucketIndex]));
        }

        render(canvas);
        return newPoints;
    }

    @Override
    public void redraw(BufferedImage canvas, int width, int height, int dotSize) {
        this.width = width;
        this.height = height;
        render(canvas);
    }

    private void clear(BufferedImage canvas) {
        Graphics2D g = canvas.createGraphics();
        g.setColor(BACKGROUND_COLOR);
        g.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
        g.dispose();
    }

    private void render(BufferedImage canvas) {
        if (canvas == null) {
            return;
        }

        this.width = canvas.getWidth();
        this.height = canvas.getHeight();

        int[] pixels = new int[width * height];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = BACKGROUND_RGB;
        }
        canvas.setRGB(0, 0, width, height, pixels, 0, width);

        Graphics2D g = canvas.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        drawBoard(g);
        drawHistogram(g);
        drawExpectedCurve(g);
        drawLegend(g);

        g.dispose();
    }

    private void drawBoard(Graphics2D g) {
        int usableWidth = Math.max(1, width - OUTER_MARGIN * 2);
        int boardBottom = Math.max(TOP_MARGIN + 1, height - BOARD_BOTTOM_GAP);
        double verticalGap = (double) (boardBottom - TOP_MARGIN) / Math.max(1, LEVELS);
        double horizontalGap = (double) usableWidth / Math.max(1, LEVELS + 1);
        int centerX = width / 2;

        for (int row = 0; row < LEVELS; row++) {
            double y = TOP_MARGIN + row * verticalGap;

            for (int col = 0; col <= row; col++) {
                double x = centerX + (col - row / 2.0) * horizontalGap;
                drawPeg(g, (int) Math.round(x), (int) Math.round(y), row);
            }
        }
    }

    private void drawPeg(Graphics2D g, int x, int y, int row) {
        Color color = row % 4 == 0 ? PEG_HIGHLIGHT_COLOR : PEG_COLOR;
        g.setColor(color);
        g.fillOval(x - PEG_RADIUS, y - PEG_RADIUS, PEG_RADIUS * 2, PEG_RADIUS * 2);
    }

    private void drawHistogram(Graphics2D g) {
        int maxBucket = getMaxBucket();
        if (maxBucket == 0) {
            return;
        }

        int histogramTop = Math.max(TOP_MARGIN + 80, height - BOARD_BOTTOM_GAP + HISTOGRAM_TOP_GAP);
        int histogramBottom = height - HISTOGRAM_BOTTOM_MARGIN;
        int histogramHeight = Math.max(1, histogramBottom - histogramTop);
        int totalWidth = Math.max(1, width - OUTER_MARGIN * 2);
        int barWidth = Math.max(MIN_BAR_WIDTH, totalWidth / BUCKETS - BAR_GAP);
        int barsWidth = BUCKETS * (barWidth + BAR_GAP) - BAR_GAP;
        int startX = Math.max(OUTER_MARGIN, (width - barsWidth) / 2);

        for (int i = 0; i < BUCKETS; i++) {
            double normalized = (double) buckets[i] / maxBucket;
            int barHeight = (int) Math.round(normalized * histogramHeight);
            int x = startX + i * (barWidth + BAR_GAP);
            int y = histogramBottom - barHeight;

            g.setColor(getBarColor(i, normalized));
            g.fillRoundRect(x, y, barWidth, barHeight, 4, 4);
            g.setColor(BAR_BORDER_COLOR);
            g.drawRoundRect(x, y, barWidth, barHeight, 4, 4);
        }
    }

    private void drawExpectedCurve(Graphics2D g) {
        if (pointCount <= 0) {
            return;
        }

        int maxBucket = getMaxBucket();
        if (maxBucket == 0) {
            return;
        }

        int histogramTop = Math.max(TOP_MARGIN + 80, height - BOARD_BOTTOM_GAP + HISTOGRAM_TOP_GAP);
        int histogramBottom = height - HISTOGRAM_BOTTOM_MARGIN;
        int histogramHeight = Math.max(1, histogramBottom - histogramTop);
        int totalWidth = Math.max(1, width - OUTER_MARGIN * 2);
        int barWidth = Math.max(MIN_BAR_WIDTH, totalWidth / BUCKETS - BAR_GAP);
        int barsWidth = BUCKETS * (barWidth + BAR_GAP) - BAR_GAP;
        int startX = Math.max(OUTER_MARGIN, (width - barsWidth) / 2);

        double[] expected = calculateExpectedBinomialCounts();
        double maxExpected = 0.0;
        for (double value : expected) {
            maxExpected = Math.max(maxExpected, value);
        }

        if (maxExpected == 0.0) {
            return;
        }

        g.setColor(EXPECTED_CURVE_COLOR);
        g.setStroke(new BasicStroke(2f));

        int previousX = -1;
        int previousY = -1;

        for (int i = 0; i < BUCKETS; i++) {
            int x = startX + i * (barWidth + BAR_GAP) + barWidth / 2;
            int y = histogramBottom - (int) Math.round((expected[i] / maxExpected) * histogramHeight);

            if (previousX >= 0) {
                g.drawLine(previousX, previousY, x, y);
            }

            previousX = x;
            previousY = y;
        }
    }

    private void drawLegend(Graphics2D g) {
        g.setFont(new Font("SansSerif", Font.PLAIN, SMALL_FONT_SIZE));
        g.setColor(TEXT_COLOR);
        g.drawString("Galton Board: balls=" + pointCount + ", levels=" + LEVELS, DISTRIBUTION_LABEL_X, DISTRIBUTION_LABEL_Y);

        g.setFont(new Font("SansSerif", Font.PLAIN, LEGEND_FONT_SIZE));
        g.setColor(SUBTLE_TEXT_COLOR);
        g.drawString("Each level consumes one random bit. Yellow line = expected binomial shape.",
                DISTRIBUTION_LABEL_X,
                DISTRIBUTION_LABEL_Y + 18);
    }

    private Color getBarColor(int bucketIndex, double normalized) {
        double center = (BUCKETS - 1) / 2.0;
        double distanceFromCenter = Math.abs(bucketIndex - center) / center;
        float hue = (float) (0.62 - 0.50 * (1.0 - distanceFromCenter));
        float saturation = 0.82f;
        float brightness = (float) (0.45 + 0.55 * normalized);
        return Color.getHSBColor(hue, saturation, brightness);
    }

    private double[] calculateExpectedBinomialCounts() {
        double[] expected = new double[BUCKETS];
        double probability = Math.pow(0.5, LEVELS);

        for (int k = 0; k < BUCKETS; k++) {
            expected[k] = probability * pointCount;

            if (k < LEVELS) {
                probability *= (double) (LEVELS - k) / (k + 1);
            }
        }

        return expected;
    }

    private int getMaxBucket() {
        int max = 0;
        for (int bucket : buckets) {
            max = Math.max(max, bucket);
        }
        return max;
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
