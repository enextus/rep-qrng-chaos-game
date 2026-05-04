package org.ThreeDotsSierpinski;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalInt;

/**
 * Режим визуализации: Diffusion-Limited Aggregation (DLA) / Brownian Tree.
 * Частицы блуждают случайно и прилипают к растущему кластеру.
 * Визуализация «Using spheres of decreasing radius» (Paul Bourke).
 * Цвет: HSB-градиент от центра (красный/тёплый) к краям (голубой/холодный).
 * Размер: убывает с расстоянием от центра.
 * Фон: чёрный.
 *
 * @see <a href="https://paulbourke.net/fractals/dla/">Paul Bourke: DLA</a>
 */
public class DLAMode implements VisualizationMode {

    private static final String ID = "dla";
    private static final String NAME = "DLA / Brownian Tree";
    private static final String DESCRIPTION =
            "Частицы блуждают случайно и прилипают к кластеру.\n"
                    + "Кораллы, молнии, кристаллы — из чистой случайности.";
    private static final String ICON = "⚡";

    private static final int INITIAL_POINT_COUNT = 0;
    private static final int INITIAL_RANDOM_NUMBERS_USED = 0;
    private static final int DEFAULT_BASE_DOT_SIZE = 5;

    private static final int CENTER_DIVISOR = 2;

    private static final double INITIAL_MAX_DISTANCE = 1.0;
    private static final int INITIAL_SPAWN_RADIUS = 30;

    private static final int PARALLEL_WALKERS = 40;
    private static final int MAX_STEPS_PER_TICK = 5_000;
    private static final int MAX_STICKS_PER_TICK = 20;
    private static final int WALKER_LIFESPAN = 5_000;

    private static final int FULL_CIRCLE_DEGREES = 360;

    private static final int MIN_WALKER_COORDINATE = 1;
    private static final int OUTER_BORDER_EXCLUSIVE_OFFSET = 1;
    private static final int INNER_BORDER_MAX_OFFSET = 2;

    private static final double SPAWN_RADIUS_TELEPORT_MULTIPLIER = 2.0;
    private static final double SPAWN_RADIUS_EXPANSION_TRIGGER_PADDING = 20.0;
    private static final int SPAWN_RADIUS_EXPANSION_PADDING = 30;
    private static final int SPAWN_RADIUS_MAX_MARGIN = 10;

    private static final double MIN_DEPTH = 0.0;
    private static final double MAX_DEPTH = 1.0;
    private static final double SEED_DEPTH = 0.0;

    private static final double HUE_MAX = 0.5;
    private static final double SATURATION_BASE = 0.85;
    private static final double SATURATION_EXTRA = 0.15;
    private static final double BRIGHTNESS_BASE = 0.95;
    private static final double BRIGHTNESS_DROP = 0.25;

    private static final double SIZE_SCALE_CENTER = 2.5;
    private static final double SIZE_SCALE_DROP = 2.0;
    private static final int MIN_DOT_SIZE = 2;

    private static final int NEIGHBOR_OFFSET_MIN = -1;
    private static final int NEIGHBOR_OFFSET_MAX = 1;
    private static final int SELF_OFFSET = 0;

    private static final int[][] WALK_DIRS = {
            {0, -1},
            {0, 1},
            {-1, 0},
            {1, 0}
    };

    private boolean[][] grid;
    private int width;
    private int height;
    private int pointCount = INITIAL_POINT_COUNT;
    private int randomNumbersUsed = INITIAL_RANDOM_NUMBERS_USED;
    private int baseDotSize = DEFAULT_BASE_DOT_SIZE;

    private int centerX;
    private int centerY;
    private double maxDist = INITIAL_MAX_DISTANCE;

    private int spawnRadius = INITIAL_SPAWN_RADIUS;

    private final int[] walkerX = new int[PARALLEL_WALKERS];
    private final int[] walkerY = new int[PARALLEL_WALKERS];
    private final int[] walkerAge = new int[PARALLEL_WALKERS];
    private final boolean[] walkerAlive = new boolean[PARALLEL_WALKERS];

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
        this.grid = new boolean[width][height];
        this.pointCount = INITIAL_POINT_COUNT;
        this.randomNumbersUsed = INITIAL_RANDOM_NUMBERS_USED;
        this.centerX = width / CENTER_DIVISOR;
        this.centerY = height / CENTER_DIVISOR;
        this.maxDist = INITIAL_MAX_DISTANCE;
        this.spawnRadius = INITIAL_SPAWN_RADIUS;

        var g2d = canvas.createGraphics();
        g2d.setColor(Color.BLACK);
        g2d.fillRect(0, 0, width, height);

        grid[centerX][centerY] = true;
        pointCount = 1;

        g2d.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON
        );

        int seedSize = getSizeForDepth(SEED_DEPTH);
        g2d.setColor(getColorForDepth(SEED_DEPTH));
        g2d.fillOval(
                centerX - seedSize / CENTER_DIVISOR,
                centerY - seedSize / CENTER_DIVISOR,
                seedSize,
                seedSize
        );

        g2d.dispose();

        Arrays.fill(walkerAlive, false);
    }

    @Override
    public List<Point> step(RNProvider provider, BufferedImage canvas, int dotSize) {
        this.baseDotSize = dotSize;
        var newPoints = new ArrayList<Point>();

        var g2d = canvas.createGraphics();
        g2d.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON
        );

        int sticksThisTick = 0;
        int stepsLeft = MAX_STEPS_PER_TICK;

        while (stepsLeft > 0 && sticksThisTick < MAX_STICKS_PER_TICK) {
            boolean bufferEmpty = false;

            for (int i = 0; i < PARALLEL_WALKERS && stepsLeft > 0; i++) {
                if (!walkerAlive[i]) {
                    if (!spawnWalker(provider, i)) {
                        bufferEmpty = true;
                        break;
                    }
                }

                OptionalInt dirOpt = provider.getNextRandomNumber();
                if (dirOpt.isEmpty()) {
                    bufferEmpty = true;
                    break;
                }

                int dir = Math.abs(dirOpt.getAsInt()) % WALK_DIRS.length;
                randomNumbersUsed++;
                stepsLeft--;

                walkerX[i] += WALK_DIRS[dir][0];
                walkerY[i] += WALK_DIRS[dir][1];
                walkerAge[i]++;

                if (isOutsideCanvasBorder(walkerX[i], walkerY[i])) {
                    walkerAlive[i] = false;
                    continue;
                }

                if (walkerAge[i] > WALKER_LIFESPAN) {
                    walkerAlive[i] = false;
                    continue;
                }

                int dx = walkerX[i] - centerX;
                int dy = walkerY[i] - centerY;
                double distSquared = (double) (dx * dx + dy * dy);

                double maxAllowedDist = spawnRadius * SPAWN_RADIUS_TELEPORT_MULTIPLIER;
                if (distSquared > maxAllowedDist * maxAllowedDist) {
                    teleportWalkerToBorder(provider, i);
                    continue;
                }

                if (touchesCluster(walkerX[i], walkerY[i])) {
                    grid[walkerX[i]][walkerY[i]] = true;
                    pointCount++;
                    walkerAlive[i] = false;

                    if (distSquared > maxDist * maxDist) {
                        maxDist = Math.sqrt(distSquared);
                    }

                    double dist = Math.sqrt(distSquared);
                    if (dist + SPAWN_RADIUS_EXPANSION_TRIGGER_PADDING > spawnRadius) {
                        spawnRadius = Math.min(
                                (int) dist + SPAWN_RADIUS_EXPANSION_PADDING,
                                Math.min(width, height) / CENTER_DIVISOR - SPAWN_RADIUS_MAX_MARGIN
                        );
                    }

                    double t = maxDist == 0 ? MIN_DEPTH : dist / maxDist;
                    int size = getSizeForDepth(t);
                    Color color = getColorForDepth(t);

                    g2d.setColor(color);
                    g2d.fillOval(
                            walkerX[i] - size / CENTER_DIVISOR,
                            walkerY[i] - size / CENTER_DIVISOR,
                            size,
                            size
                    );

                    newPoints.add(new Point(walkerX[i], walkerY[i]));
                    sticksThisTick++;

                    if (sticksThisTick >= MAX_STICKS_PER_TICK) {
                        break;
                    }
                }
            }

            if (bufferEmpty) {
                break;
            }
        }

        g2d.dispose();
        return newPoints;
    }

    private Color getColorForDepth(double t) {
        t = Math.clamp(t, MIN_DEPTH, MAX_DEPTH);

        float hue = (float) (t * HUE_MAX);
        float saturation = (float) (SATURATION_BASE + SATURATION_EXTRA * (MAX_DEPTH - t));
        float brightness = (float) (BRIGHTNESS_BASE - BRIGHTNESS_DROP * t);

        return Color.getHSBColor(hue, saturation, brightness);
    }

    private int getSizeForDepth(double t) {
        t = Math.clamp(t, MIN_DEPTH, MAX_DEPTH);

        double scale = SIZE_SCALE_CENTER - SIZE_SCALE_DROP * t;

        return Math.max(MIN_DOT_SIZE, (int) (baseDotSize * scale));
    }

    private boolean spawnWalker(RNProvider provider, int index) {
        OptionalInt angleOpt = provider.getNextRandomNumber();
        if (angleOpt.isEmpty()) {
            walkerAlive[index] = false;
            return false;
        }

        int angle = Math.abs(angleOpt.getAsInt()) % FULL_CIRCLE_DEGREES;
        randomNumbersUsed++;

        placeWalkerOnSpawnBorder(index, angle);

        walkerAge[index] = 0;
        walkerAlive[index] = true;

        return true;
    }

    private void teleportWalkerToBorder(RNProvider provider, int index) {
        OptionalInt angleOpt = provider.getNextRandomNumber();
        if (angleOpt.isEmpty()) {
            walkerAlive[index] = false;
            return;
        }

        int angle = Math.abs(angleOpt.getAsInt()) % FULL_CIRCLE_DEGREES;
        randomNumbersUsed++;

        placeWalkerOnSpawnBorder(index, angle);

        walkerAge[index] = 0;
        walkerAlive[index] = true;
    }

    private void placeWalkerOnSpawnBorder(int index, int angle) {
        double rad = Math.toRadians(angle);

        walkerX[index] = centerX + (int) (spawnRadius * Math.cos(rad));
        walkerY[index] = centerY + (int) (spawnRadius * Math.sin(rad));

        walkerX[index] = Math.clamp(
                walkerX[index],
                MIN_WALKER_COORDINATE,
                width - INNER_BORDER_MAX_OFFSET
        );

        walkerY[index] = Math.clamp(
                walkerY[index],
                MIN_WALKER_COORDINATE,
                height - INNER_BORDER_MAX_OFFSET
        );
    }

    private boolean isOutsideCanvasBorder(int x, int y) {
        return x < MIN_WALKER_COORDINATE
                || x >= width - OUTER_BORDER_EXCLUSIVE_OFFSET
                || y < MIN_WALKER_COORDINATE
                || y >= height - OUTER_BORDER_EXCLUSIVE_OFFSET;
    }

    private boolean touchesCluster(int x, int y) {
        for (int dx = NEIGHBOR_OFFSET_MIN; dx <= NEIGHBOR_OFFSET_MAX; dx++) {
            for (int dy = NEIGHBOR_OFFSET_MIN; dy <= NEIGHBOR_OFFSET_MAX; dy++) {
                if (dx == SELF_OFFSET && dy == SELF_OFFSET) {
                    continue;
                }

                int nx = x + dx;
                int ny = y + dy;

                if (isInsideGrid(nx, ny) && grid[nx][ny]) {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean isInsideGrid(int x, int y) {
        return x >= 0 && x < width && y >= 0 && y < height;
    }

    private static double distance(int x1, int y1, int x2, int y2) {
        double dx = x1 - x2;
        double dy = y1 - y2;

        return Math.sqrt(dx * dx + dy * dy);
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