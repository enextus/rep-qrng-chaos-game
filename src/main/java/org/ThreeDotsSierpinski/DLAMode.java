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
 *
 * Улучшения по сравнению с базовой версией:
 *  - Birth/Kill circles: walker умирает при выходе за kill-радиус (правильная DLA),
 *    spawn-радиус ограничен реальным расстоянием от центра до края canvas, что
 *    устраняет артефакт «дуги вдоль границы».
 *  - Sticking probability < 1.0: даёт более плотные, кораллообразные структуры
 *    вместо костлявых нитей.
 *  - Цвет считается от времени рождения частицы (порядкового номера), а не от
 *    расстояния — получается эффект «годовых колец».
 *  - Палитра в стиле inferno (фиолет → пурпур → оранж → жёлтый) вместо HSB-радуги.
 *  - Трёхслойный glow + высветленное ядро дают объёмное светящееся свечение.
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

    // ---- базовые параметры ---------------------------------------------------

    private static final int INITIAL_POINT_COUNT = 0;
    private static final int INITIAL_RANDOM_NUMBERS_USED = 0;
    private static final int DEFAULT_BASE_DOT_SIZE = 5;
    private static final int CENTER_DIVISOR = 2;

    private static final double INITIAL_MAX_DISTANCE = 1.0;
    private static final int INITIAL_SPAWN_RADIUS = 30;

    private static final int PARALLEL_WALKERS = 160;
    private static final int MAX_STEPS_PER_TICK = 30_000;
    private static final int MAX_STICKS_PER_TICK = 80;
    private static final int WALKER_LIFESPAN = 3_000;

    private static final int FULL_CIRCLE_DEGREES = 360;
    private static final int MIN_WALKER_COORDINATE = 1;
    private static final int OUTER_BORDER_EXCLUSIVE_OFFSET = 1;
    private static final int INNER_BORDER_MAX_OFFSET = 2;

    // ---- birth/kill circles --------------------------------------------------

    /** Узкая полоса вокруг фронта кластера, в которой рождаются walker'ы. */
    private static final int BIRTH_BUFFER = 15;

    /**
     * Если walker уходит дальше KILL_RADIUS_MULT × spawnRadius — он умирает
     * (а не телепортируется обратно, как было раньше). Это правильная
     * каноническая DLA: walker рождается в одном кольце, погибает в другом.
     */
    private static final double KILL_RADIUS_MULT = 2.0;

    /** Запас от края canvas: spawn-круг гарантированно влезает целиком. */
    private static final int SPAWN_RADIUS_MARGIN = 20;

    // ---- стиль роста ---------------------------------------------------------

    /**
     * Вероятность прилипания при касании кластера.
     *   1.0  → классический «костлявый» DLA-фрактал
     *   0.5  → ветвистый, но всё ещё разреженный
     *   0.35 → пышная коралловая структура (default)
     *   0.1  → почти плотный Eden-кластер
     */
    private static final double STICKING_PROBABILITY = 0.35;

    // ---- цвет ----------------------------------------------------------------

    /**
     * Шкала «полупути»: при pointCount == COLOR_TIME_SCALE цвет находится
     * ровно на середине палитры (t = 0.5). Используется логистическая формула
     * t = n / (k + n), которая ассимптотически стремится к 1.0 — это даёт
     * хорошую цветовую динамику и для маленьких кластеров (5k точек), и для
     * больших (50k+), без застревания в одном сегменте палитры.
     *
     * Чем меньше значение — тем быстрее цвета прогрессируют к фронту палитры.
     *   3_000  → яркий жёлтый фронт уже при 10k точек
     *   8_000  → сбалансировано (default)
     *   20_000 → медленная прогрессия, долгий «тёмный» период
     */
    private static final double COLOR_TIME_SCALE = 6_000.0;

    /** Дополнительный вклад радиальной позиции в цвет: 0.0 = чисто по времени, 1.0 = чисто по радиусу. */
    private static final double COLOR_RADIAL_WEIGHT = 0.4;

    /** Палитра лед
     */
    private static final Color[] PALETTE = {
            new Color( 10,  20,  60),  // глубокий синий
            new Color( 30,  80, 160),  // голубой
            new Color(100, 180, 230),  // светло-голубой
            new Color(200, 230, 250),  // ледяной
            new Color(255, 255, 255)   // белый
    };

    // ---- glow / свечение -----------------------------------------------------

    /** Размер каждого halo-слоя как множитель от базового размера точки. */
    private static final double[] HALO_SCALES = {3.6, 2.3, 1.5};

    /** Прозрачность каждого halo-слоя (внешний → внутренний). */
    private static final int[] HALO_ALPHAS = {18, 45, 95};

    /** На сколько высветлять ядро относительно базового цвета (additive feel). */
    private static final int CORE_BRIGHTEN = 50;

    // ---- размер точки --------------------------------------------------------

    private static final int MIN_DOT_SIZE = 2;
    /** Базовый множитель: даже самые старые точки не меньше этого × baseDotSize. */
    private static final double SIZE_SCALE_BASE = 1.0;
    /** Дополнительный рост для свежих точек на фронте кластера. */
    private static final double SIZE_SCALE_GROWTH = 0.6;

    // ---- 8-связное случайное блуждание --------------------------------------

    private static final int[][] WALK_DIRS = {
            {0, -1}, {1, -1}, {1, 0}, {1, 1},
            {0, 1},  {-1, 1}, {-1, 0}, {-1, -1}
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

    // ---- состояние -----------------------------------------------------------

    private boolean[] grid;
    private int width;
    private int height;
    private int pointCount = INITIAL_POINT_COUNT;
    private int randomNumbersUsed = INITIAL_RANDOM_NUMBERS_USED;
    private int baseDotSize = DEFAULT_BASE_DOT_SIZE;

    private int centerX;
    private int centerY;
    /** Аккуратный кэп spawn-радиуса — гарантирует, что круг влезает в canvas. */
    private int maxRadiusCap;
    private double maxDist = INITIAL_MAX_DISTANCE;
    private double maxDistSquared = INITIAL_MAX_DISTANCE * INITIAL_MAX_DISTANCE;

    private int spawnRadius = INITIAL_SPAWN_RADIUS;

    private final int[] walkerX = new int[PARALLEL_WALKERS];
    private final int[] walkerY = new int[PARALLEL_WALKERS];
    private final int[] walkerAge = new int[PARALLEL_WALKERS];
    private final boolean[] walkerAlive = new boolean[PARALLEL_WALKERS];

    // ==== интерфейсные методы ================================================

    @Override public String getId()          { return ID; }
    @Override public String getName()        { return NAME; }
    @Override public String getDescription() { return DESCRIPTION; }
    @Override public String getIcon()        { return ICON; }
    @Override public boolean usesRecolorAnimation() { return false; }
    @Override public boolean usesDarkBackground()    { return true; }
    @Override public int getPointCount()             { return pointCount; }
    @Override public int getRandomNumbersUsed()      { return randomNumbersUsed; }

    // ==== основной цикл =======================================================

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

        // Правильный кэп: spawn-круг радиуса maxRadiusCap гарантированно
        // помещается в canvas со всех сторон (для любого aspect ratio).
        // Это устраняет артефакт «вертикальной дуги», который возникал из-за
        // того, что spawn-круг вылезал за границы и Math.clamp сплющивал
        // walker'ов в линию вдоль края.
        this.maxRadiusCap = Math.min(
                Math.min(centerX, width  - centerX),
                Math.min(centerY, height - centerY)
        ) - SPAWN_RADIUS_MARGIN;
        if (this.maxRadiusCap < INITIAL_SPAWN_RADIUS) {
            this.maxRadiusCap = INITIAL_SPAWN_RADIUS;
        }

        Graphics2D g2d = canvas.createGraphics();
        try {
            g2d.setColor(Color.BLACK);
            g2d.fillRect(0, 0, width, height);

            setOccupied(centerX, centerY);
            pointCount = 1;

            g2d.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON
            );

            // Seed — самый старый цвет в палитре.
            drawParticle(g2d, centerX, centerY, 0.0);
        } finally {
            g2d.dispose();
        }

        Arrays.fill(walkerAlive, false);
    }

    @Override
    public List<Point> step(RNProvider provider, BufferedImage canvas, int dotSize) {
        this.baseDotSize = Math.max(MIN_DOT_SIZE, dotSize);
        var newPoints = new ArrayList<Point>(MAX_STICKS_PER_TICK);

        Graphics2D g2d = canvas.createGraphics();
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

                    // Kill circle: walker вышел далеко — он умирает, не телепортируется.
                    double killR = spawnRadius * KILL_RADIUS_MULT;
                    if (distSquared > killR * killR) {
                        walkerAlive[i] = false;
                        continue;
                    }

                    if (touchesCluster(walkerX[i], walkerY[i])) {
                        // Вероятностное прилипание: коралл вместо нити.
                        OptionalInt rollOpt = provider.getNextRandomNumber();
                        if (rollOpt.isEmpty()) {
                            bufferEmpty = true;
                            break;
                        }
                        randomNumbersUsed++;

                        double roll = (rollOpt.getAsInt() & 0x7FFFFFFF)
                                / (double) Integer.MAX_VALUE;

                        if (roll < STICKING_PROBABILITY) {
                            stickWalker(g2d, newPoints, i, distSquared);
                            sticksThisTick++;
                            if (sticksThisTick >= MAX_STICKS_PER_TICK) {
                                break;
                            }
                        }
                        // Иначе walker продолжает блуждать — может скользнуть глубже в кластер.
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

    // ==== прилипание и отрисовка =============================================

    private void stickWalker(Graphics2D g2d, List<Point> newPoints,
                             int index, double distSquared) {
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

        // Spawn-радиус всегда чуть больше фронта кластера — но не больше канваса.
        spawnRadius = Math.min((int) maxDist + BIRTH_BUFFER, maxRadiusCap);

        // Логистическая шкала по времени: ассимптотически стремится к 1.0,
        // но никогда не застревает в одном сегменте палитры.
        double tTime = pointCount / (COLOR_TIME_SCALE + pointCount);

        // Радиальная составляющая: фронт ярче центра прямо сейчас.
        double tRadial = maxDist > 0 ? dist / maxDist : 0.0;

        // Смешиваем — даёт и пространственную, и временную динамику.
        double t = (1.0 - COLOR_RADIAL_WEIGHT) * tTime
                + COLOR_RADIAL_WEIGHT * tRadial;
        t = Math.clamp(t, 0.0, 1.0);

        drawParticle(g2d, x, y, t);
        newPoints.add(new Point(x, y));
    }

    private void drawParticle(Graphics2D g2d, int x, int y, double t) {
        int size = getSizeForDepth(t);
        Color color = getColorForDepth(t);

        // Многослойный glow: внешний (тусклый и широкий) → внутренний (яркий).
        for (int i = 0; i < HALO_SCALES.length; i++) {
            int hs = Math.max(size + 2, (int) Math.round(size * HALO_SCALES[i]));
            g2d.setColor(new Color(
                    color.getRed(), color.getGreen(), color.getBlue(),
                    HALO_ALPHAS[i]
            ));
            g2d.fillOval(x - hs / CENTER_DIVISOR, y - hs / CENTER_DIVISOR, hs, hs);
        }

        // Высветленное ядро.
        Color core = new Color(
                Math.min(255, color.getRed()   + CORE_BRIGHTEN),
                Math.min(255, color.getGreen() + CORE_BRIGHTEN),
                Math.min(255, color.getBlue()  + CORE_BRIGHTEN)
        );
        g2d.setColor(core);
        g2d.fillOval(x - size / CENTER_DIVISOR, y - size / CENTER_DIVISOR, size, size);
    }

    /** Линейная интерполяция по PALETTE. */
    private Color getColorForDepth(double t) {
        t = Math.clamp(t, 0.0, 1.0);
        double scaled = t * (PALETTE.length - 1);
        int idx = (int) scaled;
        if (idx >= PALETTE.length - 1) {
            return PALETTE[PALETTE.length - 1];
        }
        double f = scaled - idx;
        Color a = PALETTE[idx];
        Color b = PALETTE[idx + 1];
        return new Color(
                (int) (a.getRed()   + (b.getRed()   - a.getRed())   * f),
                (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * f),
                (int) (a.getBlue()  + (b.getBlue()  - a.getBlue())  * f)
        );
    }

    private int getSizeForDepth(double t) {
        t = Math.clamp(t, 0.0, 1.0);
        double scale = SIZE_SCALE_BASE + SIZE_SCALE_GROWTH * t;
        return Math.max(MIN_DOT_SIZE, (int) (baseDotSize * scale));
    }

    // ==== walker управление ==================================================

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

    private void placeWalkerOnSpawnBorder(int index, int angle) {
        walkerX[index] = centerX + (int) (spawnRadius * ANGLE_COS[angle]);
        walkerY[index] = centerY + (int) (spawnRadius * ANGLE_SIN[angle]);

        // Clamp всё ещё нужен как защита, но при правильном maxRadiusCap
        // он почти никогда не срабатывает (и не создаёт линейных артефактов).
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
}
