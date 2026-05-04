package org.ThreeDotsSierpinski;

import java.awt.*;
import java.awt.image.BufferedImage;
import javax.swing.*;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

/**
 * Режим визуализации: диаграмма Вороного с Lloyd's Relaxation.
 * Случайные точки становятся центрами ячеек. Каждый тик добавляется
 * новая точка, пересчитывается мозаика. Цвет зависит от «возраста».
 * Каждые N шагов запускается Lloyd's Relaxation — точки сдвигаются
 * в центроиды своих ячеек, создавая более органичные формы.
 */
public class VoronoiMode implements VisualizationMode {

    private static final int SEEDS_PER_STEP = 2;
    private static final int MAX_SEEDS = 80;          // ограничение для производительности
    private static final int LLOYD_INTERVAL = 20;     // каждые 20 шагов — релаксация
    private static final float BORDER_DARKNESS = 0.25f;

    private static final String MARKERS_LABEL_TEXT = "Метки";
    private static final String MARKERS_ON_TEXT = "ВКЛ.";
    private static final String MARKERS_OFF_TEXT = "ВЫКЛ.";
    private static final String MARKERS_TOOLTIP_TEXT = "Показать/скрыть белые метки seed-центров";

    private static final int MARKER_HALF_SIZE = 2;
    private static final int MARKER_TOGGLE_WIDTH = 70;
    private static final int MARKER_TOGGLE_HEIGHT = 28;

    private int width;
    private int height;
    private List<Seed> seeds;
    private int pointCount = 0;
    private int randomNumbersUsed = 0;
    private int stepCount = 0;
    private boolean showCenterMarkers = true;

    private record Seed(int x, int y, Color color, int age) {}

    @Override
    public String getId() { return "voronoi"; }

    @Override
    public String getName() { return "Voronoi Mosaic"; }

    @Override
    public String getDescription() {
        return "Случайные точки порождают ячейки Вороного.\n"
                + "Каждая новая точка меняет мозаику — хаос становится структурой.";
    }

    @Override
    public String getIcon() { return "🌐"; }

    @Override
    public boolean usesRecolorAnimation() { return false; }

    @Override
    public boolean usesDarkBackground() { return true; }

    @Override
    public List<JComponent> createModeControls(DotController controller) {
        var label = new JLabel(MARKERS_LABEL_TEXT);
        var toggle = new JToggleButton();

        toggle.putClientProperty("JToggleButton.buttonType", "toggle");
        toggle.setSelected(showCenterMarkers);
        toggle.setPreferredSize(new Dimension(MARKER_TOGGLE_WIDTH, MARKER_TOGGLE_HEIGHT));
        toggle.setToolTipText(MARKERS_TOOLTIP_TEXT);

        Runnable syncToggleText = () ->
                toggle.setText(showCenterMarkers ? MARKERS_ON_TEXT : MARKERS_OFF_TEXT);
        syncToggleText.run();

        toggle.addActionListener(_ -> {
            showCenterMarkers = toggle.isSelected();
            syncToggleText.run();
            controller.refreshVisualization();
        });

        return List.of(label, toggle);
    }

    @Override
    public void redraw(BufferedImage canvas, int width, int height, int dotSize) {
        this.width = width;
        this.height = height;
        renderVoronoi(canvas);
    }

    @Override
    public void initialize(BufferedImage canvas, int width, int height) {
        this.width = width;
        this.height = height;
        this.seeds = new ArrayList<>();
        this.pointCount = 0;
        this.randomNumbersUsed = 0;
        this.stepCount = 0;

        Graphics2D g = canvas.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, width, height);
        g.dispose();
    }

    @Override
    public List<Point> step(RNProvider provider, BufferedImage canvas, int dotSize) {
        List<Point> newPoints = new ArrayList<>();

        // Добавляем новые seed из случайных чисел
        for (int i = 0; i < SEEDS_PER_STEP; i++) {
            OptionalInt rx = provider.getNextRandomNumber();
            OptionalInt ry = provider.getNextRandomNumber();
            if (rx.isEmpty() || ry.isEmpty()) break;

            int x = Math.abs(rx.getAsInt()) % width;
            int y = Math.abs(ry.getAsInt()) % height;
            randomNumbersUsed += 2;

            // Hue: градиент по возрасту + небольшой сдвиг по X для разнообразия
            float hue = (stepCount * 0.025f + (float) x / width * 0.3f) % 1.0f;
            float sat = 0.72f;
            float bri = 0.88f;
            Color color = Color.getHSBColor(hue, sat, bri);

            seeds.add(new Seed(x, y, color, stepCount));
            pointCount++;
            newPoints.add(new Point(x, y));
        }

        // Удаляем самые старые, если превысили лимит (циклический эффект)
        while (seeds.size() > MAX_SEEDS) {
            seeds.removeFirst();
        }

        // Lloyd's Relaxation: сдвигаем точки к центроидам ячеек
        if (stepCount > 0 && stepCount % LLOYD_INTERVAL == 0 && seeds.size() > 2) {
            seeds = lloydRelax(seeds);
        }

        // Перерисовываем всю диаграмму
        renderVoronoi(canvas);

        stepCount++;
        return newPoints;
    }

    /**
     * Наивный, но надёжный рендер: для каждого пикселя ищем ближайший seed.
     * Границы ячеек затемняются для эффекта мозаики.
     */
    private void renderVoronoi(BufferedImage canvas) {
        int[] pixels = new int[width * height];

        // Фон — чёрный
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = 0xFF000000;
        }

        if (seeds.isEmpty()) {
            canvas.setRGB(0, 0, width, height, pixels, 0, width);
            return;
        }

        int n = seeds.size();

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int nearestIdx = 0;
                double minDist = Double.MAX_VALUE;
                double secondDist = Double.MAX_VALUE;

                for (int i = 0; i < n; i++) {
                    Seed s = seeds.get(i);
                    double dx = x - s.x;
                    double dy = y - s.y;
                    double dist = dx * dx + dy * dy; // без sqrt — скорость

                    if (dist < minDist) {
                        secondDist = minDist;
                        minDist = dist;
                        nearestIdx = i;
                    } else if (dist < secondDist) {
                        secondDist = dist;
                    }
                }

                Seed nearest = seeds.get(nearestIdx);
                int rgb = nearest.color.getRGB();

                // Граница ячейки: если разница расстояний мала — делаем тёмную линию
                double diff = Math.sqrt(secondDist) - Math.sqrt(minDist);
                if (diff < 1.8) {
                    int r = (int) (nearest.color.getRed() * BORDER_DARKNESS);
                    int g = (int) (nearest.color.getGreen() * BORDER_DARKNESS);
                    int b = (int) (nearest.color.getBlue() * BORDER_DARKNESS);
                    rgb = (0xFF << 24) | (r << 16) | (g << 8) | b;
                }

                pixels[y * width + x] = rgb;
            }
        }

        canvas.setRGB(0, 0, width, height, pixels, 0, width);

        if (showCenterMarkers) {
            drawCenterMarkers(canvas);
        }
    }

    /**
     * Рисует seed-точки белым перекрестием.
     */
    private void drawCenterMarkers(BufferedImage canvas) {
        Graphics2D g = canvas.createGraphics();
        g.setColor(Color.WHITE);

        for (Seed s : seeds) {
            g.drawLine(s.x - MARKER_HALF_SIZE, s.y, s.x + MARKER_HALF_SIZE, s.y);
            g.drawLine(s.x, s.y - MARKER_HALF_SIZE, s.x, s.y + MARKER_HALF_SIZE);
        }

        g.dispose();
    }

    /**
     * Lloyd's Relaxation: перемещает каждую точку в центроид своей ячейки.
     * Делает распределение более равномерным и «органичным».
     */
    private List<Seed> lloydRelax(List<Seed> oldSeeds) {
        int n = oldSeeds.size();
        long[] sumX = new long[n];
        long[] sumY = new long[n];
        int[] count = new int[n];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int nearest = 0;
                double minDist = Double.MAX_VALUE;

                for (int i = 0; i < n; i++) {
                    Seed s = oldSeeds.get(i);
                    double dx = x - s.x;
                    double dy = y - s.y;
                    double dist = dx * dx + dy * dy;
                    if (dist < minDist) {
                        minDist = dist;
                        nearest = i;
                    }
                }
                sumX[nearest] += x;
                sumY[nearest] += y;
                count[nearest]++;
            }
        }

        List<Seed> relaxed = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (count[i] > 0) {
                int nx = (int) (sumX[i] / count[i]);
                int ny = (int) (sumY[i] / count[i]);
                relaxed.add(new Seed(nx, ny, oldSeeds.get(i).color, oldSeeds.get(i).age));
            } else {
                relaxed.add(oldSeeds.get(i));
            }
        }
        return relaxed;
    }

    @Override
    public int getPointCount() { return pointCount; }

    @Override
    public int getRandomNumbersUsed() { return randomNumbersUsed; }
}