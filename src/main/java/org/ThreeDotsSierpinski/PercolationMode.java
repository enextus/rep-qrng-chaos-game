package org.ThreeDotsSierpinski;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

/**
 * Режим визуализации: site percolation на квадратной решётке.
 * <p>
 * Каждая клетка получает случайное решение: открыть или оставить закрытой.
 * После каждого шага пересчитывается кластер, связанный с верхней границей.
 * Если этот кластер достигает нижней границы, возникает percolation path.
 */
public class PercolationMode implements VisualizationMode {

    private static final String ID = "percolation";
    private static final String NAME = "Percolation";
    private static final String DESCRIPTION =
            "Случайные клетки открывают кластеры связности.\n"
          + "Около p≈0.59 появляется путь через всю решётку.";
    private static final String ICON = "🧩";

    private static final int CELL_SIZE = 7;
    private static final int MIN_GRID_SIZE = 8;
    private static final int MAX_GRID_COLUMNS = 180;
    private static final int MAX_GRID_ROWS = 140;
    private static final int CELLS_PER_STEP = 180;

    private static final int PROBABILITY_SCALE = 10_000;
    private static final int DEFAULT_OPEN_PROBABILITY = 5_927;
    private static final int MIN_OPEN_PROBABILITY = 1_000;
    private static final int MAX_OPEN_PROBABILITY = 9_000;
    private static final int OPEN_PROBABILITY_STEP = 500;

    private static final String PROBABILITY_LABEL_PREFIX = "p=";
    private static final String DECREASE_PROBABILITY_TEXT = "p-";
    private static final String INCREASE_PROBABILITY_TEXT = "p+";
    private static final String RESET_TEXT = "Reset";
    private static final String RESET_TOOLTIP = "Reset percolation grid";
    private static final int SMALL_BUTTON_WIDTH = 52;
    private static final int RESET_BUTTON_WIDTH = 70;
    private static final int CONTROL_HEIGHT = 28;

    private static final int BACKGROUND_RGB = 0xFF000000;
    private static final int CLOSED_RGB = 0xFF05070A;
    private static final Color GRID_LINE_COLOR = new Color(18, 26, 36);
    private static final Color OPEN_COLOR = new Color(75, 95, 125);
    private static final Color CONNECTED_COLOR = new Color(65, 215, 225);
    private static final Color SPANNING_COLOR = new Color(255, 215, 90);
    private static final Color TEXT_COLOR = new Color(200, 220, 255);
    private static final Color SUBTLE_TEXT_COLOR = new Color(135, 150, 180);

    private static final int LEGEND_X = 12;
    private static final int LEGEND_Y = 22;
    private static final int LEGEND_LINE_HEIGHT = 18;
    private static final int LEGEND_FONT_SIZE = 12;
    private static final int LEGEND_SMALL_FONT_SIZE = 11;

    private int width;
    private int height;
    private int columns;
    private int rows;
    private int openProbability = DEFAULT_OPEN_PROBABILITY;
    private int nextCellIndex = 0;
    private int pointCount = 0;
    private int randomNumbersUsed = 0;
    private boolean percolates = false;

    private boolean[] decided;
    private boolean[] open;
    private boolean[] connectedToTop;

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
    public List<JComponent> createModeControls(DotController controller) {
        var probabilityLabel = new JLabel(formatProbabilityLabel());
        var decreaseButton = new JButton(DECREASE_PROBABILITY_TEXT);
        var increaseButton = new JButton(INCREASE_PROBABILITY_TEXT);
        var resetButton = new JButton(RESET_TEXT);

        decreaseButton.setPreferredSize(new Dimension(SMALL_BUTTON_WIDTH, CONTROL_HEIGHT));
        increaseButton.setPreferredSize(new Dimension(SMALL_BUTTON_WIDTH, CONTROL_HEIGHT));
        resetButton.setPreferredSize(new Dimension(RESET_BUTTON_WIDTH, CONTROL_HEIGHT));
        resetButton.setToolTipText(RESET_TOOLTIP);

        decreaseButton.addActionListener(_ -> {
            openProbability = Math.max(MIN_OPEN_PROBABILITY, openProbability - OPEN_PROBABILITY_STEP);
            probabilityLabel.setText(formatProbabilityLabel());
            resetAndRefresh(controller);
        });

        increaseButton.addActionListener(_ -> {
            openProbability = Math.min(MAX_OPEN_PROBABILITY, openProbability + OPEN_PROBABILITY_STEP);
            probabilityLabel.setText(formatProbabilityLabel());
            resetAndRefresh(controller);
        });

        resetButton.addActionListener(_ -> resetAndRefresh(controller));

        return List.of(probabilityLabel, decreaseButton, increaseButton, resetButton);
    }

    @Override
    public void initialize(BufferedImage canvas, int width, int height) {
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        this.columns = Math.clamp(this.width / CELL_SIZE, MIN_GRID_SIZE, MAX_GRID_COLUMNS);
        this.rows = Math.clamp(this.height / CELL_SIZE, MIN_GRID_SIZE, MAX_GRID_ROWS);

        resetGridState();
        render(canvas);
    }

    @Override
    public List<Point> step(RNProvider provider, BufferedImage canvas, int dotSize) {
        if (open == null || decided == null || connectedToTop == null) {
            initialize(canvas, canvas.getWidth(), canvas.getHeight());
        }

        var changedCells = new ArrayList<Point>();
        int cellsTotal = columns * rows;
        int processedThisStep = 0;

        while (processedThisStep < CELLS_PER_STEP && nextCellIndex < cellsTotal) {
            OptionalInt randomValue = provider.getNextRandomNumber();
            if (randomValue.isEmpty()) {
                break;
            }

            int index = nextCellIndex++;
            decided[index] = true;
            open[index] = Math.floorMod(randomValue.getAsInt(), PROBABILITY_SCALE) < openProbability;
            randomNumbersUsed++;
            pointCount++;
            processedThisStep++;

            if (open[index]) {
                changedCells.add(new Point(index % columns, index / columns));
            }
        }

        recalculateConnectedCluster();
        render(canvas);
        return changedCells;
    }

    @Override
    public void redraw(BufferedImage canvas, int width, int height, int dotSize) {
        if (canvas == null) {
            return;
        }

        if (this.width != width || this.height != height || open == null) {
            initialize(canvas, width, height);
            return;
        }

        render(canvas);
    }

    private void resetAndRefresh(DotController controller) {
        resetGridState();
        if (controller != null) {
            controller.refreshVisualization();
        }
    }

    private void resetGridState() {
        int cellsTotal = Math.max(1, columns * rows);
        this.decided = new boolean[cellsTotal];
        this.open = new boolean[cellsTotal];
        this.connectedToTop = new boolean[cellsTotal];
        this.nextCellIndex = 0;
        this.pointCount = 0;
        this.randomNumbersUsed = 0;
        this.percolates = false;
    }

    private void recalculateConnectedCluster() {
        if (open == null || connectedToTop == null) {
            return;
        }

        connectedToTop = new boolean[columns * rows];
        percolates = false;

        var queue = new ArrayDeque<Integer>();
        for (int x = 0; x < columns; x++) {
            int index = toIndex(x, 0);
            if (open[index]) {
                connectedToTop[index] = true;
                queue.add(index);
            }
        }

        while (!queue.isEmpty()) {
            int index = queue.removeFirst();
            int x = index % columns;
            int y = index / columns;

            if (y == rows - 1) {
                percolates = true;
            }

            addNeighbor(queue, x - 1, y);
            addNeighbor(queue, x + 1, y);
            addNeighbor(queue, x, y - 1);
            addNeighbor(queue, x, y + 1);
        }
    }

    private void addNeighbor(ArrayDeque<Integer> queue, int x, int y) {
        if (x < 0 || x >= columns || y < 0 || y >= rows) {
            return;
        }

        int index = toIndex(x, y);
        if (open[index] && !connectedToTop[index]) {
            connectedToTop[index] = true;
            queue.add(index);
        }
    }

    private void render(BufferedImage canvas) {
        if (canvas == null) {
            return;
        }

        width = canvas.getWidth();
        height = canvas.getHeight();

        int[] pixels = new int[width * height];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = BACKGROUND_RGB;
        }
        canvas.setRGB(0, 0, width, height, pixels, 0, width);

        Graphics2D g = canvas.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            drawCells(g);
            drawLegend(g);
        } finally {
            g.dispose();
        }
    }

    private void drawCells(Graphics2D g) {
        double cellWidth = (double) width / columns;
        double cellHeight = (double) height / rows;

        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < columns; x++) {
                int index = toIndex(x, y);
                int px = (int) Math.floor(x * cellWidth);
                int py = (int) Math.floor(y * cellHeight);
                int pw = Math.max(1, (int) Math.ceil((x + 1) * cellWidth) - px);
                int ph = Math.max(1, (int) Math.ceil((y + 1) * cellHeight) - py);

                if (!decided[index]) {
                    g.setColor(new Color(CLOSED_RGB));
                } else if (!open[index]) {
                    g.setColor(new Color(20, 23, 28));
                } else if (connectedToTop[index]) {
                    g.setColor(percolates ? SPANNING_COLOR : CONNECTED_COLOR);
                } else {
                    g.setColor(OPEN_COLOR);
                }

                g.fillRect(px, py, pw, ph);
            }
        }

        g.setColor(GRID_LINE_COLOR);
        for (int x = 0; x <= columns; x++) {
            int px = (int) Math.round(x * cellWidth);
            g.drawLine(px, 0, px, height);
        }
        for (int y = 0; y <= rows; y++) {
            int py = (int) Math.round(y * cellHeight);
            g.drawLine(0, py, width, py);
        }
    }

    private void drawLegend(Graphics2D g) {
        g.setFont(new Font("SansSerif", Font.PLAIN, LEGEND_FONT_SIZE));
        g.setColor(TEXT_COLOR);
        g.drawString(
                "Percolation: cells=" + pointCount
                        + "/" + (columns * rows)
                        + ", p=" + formatProbability()
                        + ", spanning=" + (percolates ? "YES" : "no"),
                LEGEND_X,
                LEGEND_Y
        );

        g.setFont(new Font("SansSerif", Font.PLAIN, LEGEND_SMALL_FONT_SIZE));
        g.setColor(SUBTLE_TEXT_COLOR);
        g.drawString(
                "cyan/gold = top-connected cluster; gold means a top-to-bottom path exists",
                LEGEND_X,
                LEGEND_Y + LEGEND_LINE_HEIGHT
        );
    }

    private String formatProbabilityLabel() {
        return PROBABILITY_LABEL_PREFIX + formatProbability();
    }

    private String formatProbability() {
        return String.format("%.2f", openProbability / (double) PROBABILITY_SCALE);
    }

    private int toIndex(int x, int y) {
        return y * columns + x;
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
