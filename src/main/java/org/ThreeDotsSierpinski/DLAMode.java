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

    /**
     * More walkers + more steps per tick makes the tree grow much faster.
     * The algorithm is still bounded by MAX_STICKS_PER_TICK, so EDT repaint
     * remains controlled while the simulation searches more aggressively.
     */
    private static final int PARALLEL_WALKERS = 160;
    private static final int MAX_STEPS_PER_TICK = 20_000;
    private static final int MAX_STICKS_PER_TICK = 80;
    private static final int WALKER_LIFESPAN = 3_000;

    private static final int FULL_CIRCLE_DEGREES = 360;

    private static final int MIN_WALKER_COORDINATE = 1;
    private static final int OUTER_BORDER_EXCLUSIVE_OFFSET = 1;
    private static final int INNER_BORDER_MAX_OFFSET = 2;

    /**
     * A slightly tighter teleport radius keeps walkers close to the active
     * growth front and reduces wasted random-walk steps far away from cluster.
     */
    private static final double SPAWN_RADIUS_TELEPORT_MULTIPLIER = 1.75;
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

    private static final double HALO_SCALE = 1.85;
    private static final int HALO_EXTRA_SIZE = 4;
    private static final int HALO_ALPHA = 70;

    /**
     * 8-neighbour movement gives a more isotropic DLA tree than a 4-direction
     * walk and helps the visual structure expand faster in all directions.
     */
    private static final int[][] WALK_DIRS = {
            {0, -1},
            {1, -1},
            {1, 0},
            {1, 1},
            {0, 1},
            {-1, 1},
            {-1, 0},
            {-1, -1}
    };

    private static final double[] ANGLE_COS = new double[FULL_CIRCLE_DEGREES];
    private static final double[] ANGLE_SIN = new double[FULL_CIRCLE_DEGREES];

    static {
        for (int angle = 0; angle < FULL_CIRCLE_DEGREES; angle++) {
            double radians = Math.toRadians(angle);
            ANGLE_COS[angle] = Math.cos(radians);
            ANGLE_SIN[angle] = Math.sin(radians);
        }
    }

    /**
     * Flat grid is faster and more cache-friendly than boolean[width][height],
     * which creates many nested arrays and adds one extra indirection per cell.
     */
    private boolean[] grid;
    private int width;
    private int height;
    private int pointCount = INITIAL_POINT_COUNT;
    private int randomNumbersUsed = INITIAL_RANDOM_NUMBERS_USED;
    private int baseDotSize = DEFAULT_BASE_DOT_SIZE;

    private int centerX;
    private int centerY;
    private double maxDist = INITIAL_MAX_DISTANCE;
    private double maxDistSquared = INITIAL_MAX_DISTANCE * INITIAL_MAX_DISTANCE;

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
        this.grid = new boolean[width * height];
        this.pointCount = INITIAL_POINT_COUNT;
        this.randomNumbersUsed = INITIAL_RANDOM_NUMBERS_USED;
        this.centerX = width / CENTER_DIVISOR;
        this.centerY = height / CENTER_DIVISOR;
        this.maxDist = INITIAL_MAX_DISTANCE;
        this.maxDistSquared = INITIAL_MAX_DISTANCE * INITIAL_MAX_DISTANCE;
        this.spawnRadius = INITIAL_SPAWN_RADIUS;

        var g2d = canvas.createGraphics();

        try {
            g2d.setColor(Color.BLACK);
            g2d.fillRect(0, 0, width, height);

            setOccupied(centerX, centerY);
            pointCount = 1;

            g2d.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON
            );

            drawStuckParticle(g2d, centerX, centerY, SEED_DEPTH);
        } finally {
            g2d.dispose();
        }

        Arrays.fill(walkerAlive, false);
    }

    @Override
    public List<Point> step(RNProvider provider, BufferedImage canvas, int dotSize) {
        this.baseDotSize = Math.max(MIN_DOT_SIZE, dotSize);
        var newPoints = new ArrayList<Point>(MAX_STICKS_PER_TICK);

        var g2d = canvas.createGraphics();

        try {
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

                    int dir = Math.floorMod(dirOpt.getAsInt(), WALK_DIRS.length);
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
                        stickWalker(g2d, newPoints, i, distSquared);
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
        } finally {
            g2d.dispose();
        }

        return newPoints;
    }

    private void stickWalker(Graphics2D g2d, List<Point> newPoints, int index, double distSquared) {
        int x = walkerX[index];
        int y = walkerY[index];

        setOccupied(x, y);
        pointCount++;
        walkerAlive[index] = false;

        double dist = Math.sqrt(distSquared);
        if (distSquared > maxDistSquared) {
            maxDistSquared = distSquared;
            maxDist = dist;
        }

        if (dist + SPAWN_RADIUS_EXPANSION_TRIGGER_PADDING > spawnRadius) {
            spawnRadius = Math.min(
                    (int) dist + SPAWN_RADIUS_EXPANSION_PADDING,
                    Math.min(width, height) / CENTER_DIVISOR - SPAWN_RADIUS_MAX_MARGIN
            );
        }

        double t = maxDist == 0 ? MIN_DEPTH : dist / maxDist;
        drawStuckParticle(g2d, x, y, t);
        newPoints.add(new Point(x, y));
    }

    private void drawStuckParticle(Graphics2D g2d, int x, int y, double depth) {
        int size = getSizeForDepth(depth);
        Color color = getColorForDepth(depth);

        int haloSize = Math.max(size + HALO_EXTRA_SIZE, (int) Math.round(size * HALO_SCALE));
        Color haloColor = new Color(color.getRed(), color.getGreen(), color.getBlue(), HALO_ALPHA);

        g2d.setColor(haloColor);
        g2d.fillOval(
                x - haloSize / CENTER_DIVISOR,
                y - haloSize / CENTER_DIVISOR,
                haloSize,
                haloSize
        );

        g2d.setColor(color);
        g2d.fillOval(
                x - size / CENTER_DIVISOR,
                y - size / CENTER_DIVISOR,
                size,
                size
        );
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

        int angle = Math.floorMod(angleOpt.getAsInt(), FULL_CIRCLE_DEGREES);
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

        int angle = Math.floorMod(angleOpt.getAsInt(), FULL_CIRCLE_DEGREES);
        randomNumbersUsed++;

        placeWalkerOnSpawnBorder(index, angle);

        walkerAge[index] = 0;
        walkerAlive[index] = true;
    }

    private void placeWalkerOnSpawnBorder(int index, int angle) {
        walkerX[index] = centerX + (int) (spawnRadius * ANGLE_COS[angle]);
        walkerY[index] = centerY + (int) (spawnRadius * ANGLE_SIN[angle]);

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
        if (x <= 0 || x >= width - 1 || y <= 0 || y >= height - 1) {
            return false;
        }

        int row = y * width;
        int above = row - width;
        int below = row + width;

        return grid[above + x - 1]
                || grid[above + x]
                || grid[above + x + 1]
                || grid[row + x - 1]
                || grid[row + x + 1]
                || grid[below + x - 1]
                || grid[below + x]
                || grid[below + x + 1];
    }

    private void setOccupied(int x, int y) {
        grid[toIndex(x, y)] = true;
    }

    private int toIndex(int x, int y) {
        return y * width + x;
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
