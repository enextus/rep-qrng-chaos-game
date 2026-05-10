package org.ThreeDotsSierpinski;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Iterator;
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
    private static final int BALLS_TO_SPAWN_PER_STEP = 8;
    private static final int MAX_ACTIVE_BALLS = 180;

    private static final int BACKGROUND_RGB = 0xFF000000;
    private static final Color BACKGROUND_COLOR = Color.BLACK;
    private static final Color PEG_COLOR = new Color(125, 145, 175);
    private static final Color PEG_HIGHLIGHT_COLOR = new Color(190, 210, 240);
    private static final Color BALL_LEFT_COLOR = new Color(70, 190, 255);
    private static final Color BALL_CENTER_COLOR = new Color(255, 220, 80);
    private static final Color BALL_RIGHT_COLOR = new Color(255, 95, 150);
    private static final Color BALL_HIGHLIGHT_COLOR = new Color(255, 248, 205);
    private static final double BALL_CENTER_RATIO = 0.5;
    private static final double BALL_SHADOW_FACTOR = 0.32;
    private static final int BALL_SHADOW_ALPHA = 150;
    private static final double BALL_HIGHLIGHT_BLEND = 0.55;
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
    private static final int BALL_RADIUS = 6;
    private static final int BALL_SHADOW_OFFSET = 2;
    private static final int BAR_GAP = 2;
    private static final int MIN_BAR_WIDTH = 4;
    private static final int DISTRIBUTION_LABEL_X = 18;
    private static final int DISTRIBUTION_LABEL_Y = 150;
    private static final int SMALL_FONT_SIZE = 12;
    private static final int LEGEND_FONT_SIZE = 11;

    private int width;
    private int height;
    private int[] buckets;
    private List<ActiveBall> activeBalls;
    private int pointCount;
    private int randomNumbersUsed;

    private static final class ActiveBall {
        private int level;
        private int rightMoves;
    }

    private record BoardGeometry(
            int usableWidth,
            int boardBottom,
            double verticalGap,
            double horizontalGap,
            int centerX
    ) {}

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
        this.activeBalls = new ArrayList<>();
        this.pointCount = 0;
        this.randomNumbersUsed = 0;

        clear(canvas);
        render(canvas);
    }

    @Override
    public List<Point> step(RNProvider provider, BufferedImage canvas, int dotSize) {
        List<Point> completedBalls = new ArrayList<>();

        spawnBalls();
        advanceActiveBalls(provider, completedBalls);
        render(canvas);

        return completedBalls;
    }

    @Override
    public void redraw(BufferedImage canvas, int width, int height, int dotSize) {
        this.width = width;
        this.height = height;
        render(canvas);
    }

    private void spawnBalls() {
        if (activeBalls == null) {
            activeBalls = new ArrayList<>();
        }

        int availableSlots = Math.max(0, MAX_ACTIVE_BALLS - activeBalls.size());
        int ballsToSpawn = Math.min(BALLS_TO_SPAWN_PER_STEP, availableSlots);

        for (int i = 0; i < ballsToSpawn; i++) {
            activeBalls.add(new ActiveBall());
        }
    }

    private void advanceActiveBalls(RNProvider provider, List<Point> completedBalls) {
        Iterator<ActiveBall> iterator = activeBalls.iterator();

        while (iterator.hasNext()) {
            ActiveBall ball = iterator.next();
            OptionalInt randomValue = provider.getNextRandomNumber();

            if (randomValue.isEmpty()) {
                return;
            }

            randomNumbersUsed++;

            if ((randomValue.getAsInt() & 1) != 0) {
                ball.rightMoves++;
            }

            ball.level++;

            if (ball.level >= LEVELS) {
                int bucketIndex = Math.clamp(ball.rightMoves, 0, LEVELS);
                buckets[bucketIndex]++;
                pointCount++;
                completedBalls.add(new Point(bucketIndex, buckets[bucketIndex]));
                iterator.remove();
            }
        }
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
        drawActiveBalls(g);
        drawLegend(g);

        g.dispose();
    }

    private void drawBoard(Graphics2D g) {
        BoardGeometry geometry = getBoardGeometry();

        for (int row = 0; row < LEVELS; row++) {
            double y = TOP_MARGIN + row * geometry.verticalGap();

            for (int col = 0; col <= row; col++) {
                double x = geometry.centerX() + (col - row / 2.0) * geometry.horizontalGap();
                drawPeg(g, (int) Math.round(x), (int) Math.round(y), row);
            }
        }
    }

    private void drawPeg(Graphics2D g, int x, int y, int row) {
        Color color = row % 4 == 0 ? PEG_HIGHLIGHT_COLOR : PEG_COLOR;
        g.setColor(color);
        g.fillOval(x - PEG_RADIUS, y - PEG_RADIUS, PEG_RADIUS * 2, PEG_RADIUS * 2);
    }

    private void drawActiveBalls(Graphics2D g) {
        if (activeBalls == null || activeBalls.isEmpty()) {
            return;
        }

        BoardGeometry geometry = getBoardGeometry();

        for (ActiveBall ball : activeBalls) {
            Point position = getBallPosition(ball, geometry);
            drawBall(g, position.x, position.y, getBallColor(ball));
        }
    }

    private Point getBallPosition(ActiveBall ball, BoardGeometry geometry) {
        double level = Math.clamp(ball.level, 0, LEVELS);
        double x = geometry.centerX() + (ball.rightMoves - level / 2.0) * geometry.horizontalGap();
        double y = TOP_MARGIN + level * geometry.verticalGap();

        return new Point((int) Math.round(x), (int) Math.round(y));
    }

    private void drawBall(Graphics2D g, int x, int y, Color ballColor) {
        g.setColor(createShadowColor(ballColor));
        g.fillOval(
                x - BALL_RADIUS + BALL_SHADOW_OFFSET,
                y - BALL_RADIUS + BALL_SHADOW_OFFSET,
                BALL_RADIUS * 2,
                BALL_RADIUS * 2
        );

        g.setColor(ballColor);
        g.fillOval(x - BALL_RADIUS, y - BALL_RADIUS, BALL_RADIUS * 2, BALL_RADIUS * 2);

        g.setColor(createHighlightColor(ballColor));
        g.fillOval(
                x - BALL_RADIUS / 2,
                y - BALL_RADIUS / 2,
                BALL_RADIUS / 2,
                BALL_RADIUS / 2
        );
    }

    private Color getBallColor(ActiveBall ball) {
        if (ball.level <= 0) {
            return BALL_CENTER_COLOR;
        }

        double rightRatio = Math.clamp((double) ball.rightMoves / ball.level, 0.0, 1.0);

        if (rightRatio < BALL_CENTER_RATIO) {
            double t = rightRatio / BALL_CENTER_RATIO;
            return blend(BALL_LEFT_COLOR, BALL_CENTER_COLOR, t);
        }

        double t = (rightRatio - BALL_CENTER_RATIO) / BALL_CENTER_RATIO;
        return blend(BALL_CENTER_COLOR, BALL_RIGHT_COLOR, t);
    }

    private Color createShadowColor(Color color) {
        return new Color(
                (int) Math.round(color.getRed() * BALL_SHADOW_FACTOR),
                (int) Math.round(color.getGreen() * BALL_SHADOW_FACTOR),
                (int) Math.round(color.getBlue() * BALL_SHADOW_FACTOR),
                BALL_SHADOW_ALPHA
        );
    }

    private Color createHighlightColor(Color color) {
        return blend(color, BALL_HIGHLIGHT_COLOR, BALL_HIGHLIGHT_BLEND);
    }

    private Color blend(Color from, Color to, double t) {
        double clamped = Math.clamp(t, 0.0, 1.0);
        int r = (int) Math.round(from.getRed() + (to.getRed() - from.getRed()) * clamped);
        int g = (int) Math.round(from.getGreen() + (to.getGreen() - from.getGreen()) * clamped);
        int b = (int) Math.round(from.getBlue() + (to.getBlue() - from.getBlue()) * clamped);

        return new Color(r, g, b);
    }

    private void drawHistogram(Graphics2D g) {
        int maxBucket = getMaxBucket();
        if (maxBucket == 0) {
            return;
        }

        HistogramGeometry geometry = getHistogramGeometry();

        for (int i = 0; i < BUCKETS; i++) {
            double normalized = (double) buckets[i] / maxBucket;
            int barHeight = (int) Math.round(normalized * geometry.histogramHeight());
            int x = geometry.startX() + i * (geometry.barWidth() + BAR_GAP);
            int y = geometry.histogramBottom() - barHeight;

            g.setColor(getBarColor(i, normalized));
            g.fillRoundRect(x, y, geometry.barWidth(), barHeight, 4, 4);
            g.setColor(BAR_BORDER_COLOR);
            g.drawRoundRect(x, y, geometry.barWidth(), barHeight, 4, 4);
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

        HistogramGeometry geometry = getHistogramGeometry();

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
            int x = geometry.startX() + i * (geometry.barWidth() + BAR_GAP) + geometry.barWidth() / 2;
            int y = geometry.histogramBottom()
                    - (int) Math.round((expected[i] / maxExpected) * geometry.histogramHeight());

            if (previousX >= 0) {
                g.drawLine(previousX, previousY, x, y);
            }

            previousX = x;
            previousY = y;
        }
    }

    private BoardGeometry getBoardGeometry() {
        int usableWidth = Math.max(1, width - OUTER_MARGIN * 2);
        int boardBottom = Math.max(TOP_MARGIN + 1, height - BOARD_BOTTOM_GAP);
        double verticalGap = (double) (boardBottom - TOP_MARGIN) / Math.max(1, LEVELS);
        double horizontalGap = (double) usableWidth / Math.max(1, LEVELS + 1);
        int centerX = width / 2;

        return new BoardGeometry(usableWidth, boardBottom, verticalGap, horizontalGap, centerX);
    }

    private record HistogramGeometry(
            int histogramTop,
            int histogramBottom,
            int histogramHeight,
            int barWidth,
            int startX
    ) {}

    private HistogramGeometry getHistogramGeometry() {
        int histogramTop = Math.max(TOP_MARGIN + 80, height - BOARD_BOTTOM_GAP + HISTOGRAM_TOP_GAP);
        int histogramBottom = height - HISTOGRAM_BOTTOM_MARGIN;
        int histogramHeight = Math.max(1, histogramBottom - histogramTop);
        int totalWidth = Math.max(1, width - OUTER_MARGIN * 2);
        int barWidth = Math.max(MIN_BAR_WIDTH, totalWidth / BUCKETS - BAR_GAP);
        int barsWidth = BUCKETS * (barWidth + BAR_GAP) - BAR_GAP;
        int startX = Math.max(OUTER_MARGIN, (width - barsWidth) / 2);

        return new HistogramGeometry(histogramTop, histogramBottom, histogramHeight, barWidth, startX);
    }

    private void drawLegend(Graphics2D g) {
        g.setFont(new Font("SansSerif", Font.PLAIN, SMALL_FONT_SIZE));
        g.setColor(TEXT_COLOR);
        g.drawString(
                "Galton Board: balls=" + pointCount
                        + ", active=" + getActiveBallCount()
                        + ", levels=" + LEVELS,
                DISTRIBUTION_LABEL_X,
                DISTRIBUTION_LABEL_Y
        );

        g.setFont(new Font("SansSerif", Font.PLAIN, LEGEND_FONT_SIZE));
        g.setColor(SUBTLE_TEXT_COLOR);
        g.drawString("Animated balls consume one random bit per level. Color: left=blue, center=yellow, right=pink.",
                DISTRIBUTION_LABEL_X,
                DISTRIBUTION_LABEL_Y + 18);
    }

    private int getActiveBallCount() {
        return activeBalls == null ? 0 : activeBalls.size();
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
