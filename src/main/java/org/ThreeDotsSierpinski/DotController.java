package org.ThreeDotsSierpinski;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Универсальный контроллер визуализации случайных чисел.
 * <p>
 * Принимает любой {@link VisualizationMode} и управляет анимацией,
 * рендерингом, статусом и сохранением изображений.
 * <p>
 * Thread safety:
 * - Все операции с offscreenImage — только на EDT
 * - usedRandomNumbers итерируется под synchronized
 */
public class DotController extends JPanel {

    private static final String CONFIG_PANEL_WIDTH = "panel.size.width";
    private static final String CONFIG_PANEL_HEIGHT = "panel.size.height";
    private static final String CONFIG_DOT_SIZE = "dot.size";
    private static final String CONFIG_TIMER_DELAY = "timer.delay";
    private static final String CONFIG_COLUMN_WIDTH = "column.width";
    private static final String CONFIG_ROW_HEIGHT = "row.height";
    private static final String CONFIG_COLUMN_SPACING = "column.spacing";
    private static final String CONFIG_MAX_COLUMNS = "max.columns";
    private static final String CONFIG_WINDOW_SCALE_WIDTH = "window.scale.width";
    private static final String CONFIG_WINDOW_SCALE_HEIGHT = "window.scale.height";

    private static final int SIZE_WIDTH = Config.getInt(CONFIG_PANEL_WIDTH);
    private static final int SIZE_HEIGHT = Config.getInt(CONFIG_PANEL_HEIGHT);
    private static final int DOT_SIZE = Config.getInt(CONFIG_DOT_SIZE);
    private static final int TIMER_DELAY = Config.getInt(CONFIG_TIMER_DELAY);

    private static final int COLUMN_WIDTH = Config.getInt(CONFIG_COLUMN_WIDTH);
    private static final int ROW_HEIGHT = Config.getInt(CONFIG_ROW_HEIGHT);
    private static final int COLUMN_SPACING = Config.getInt(CONFIG_COLUMN_SPACING);
    private static final int MAX_COLUMNS = Config.getInt(CONFIG_MAX_COLUMNS);

    private static final int LIGHT_MODE_EXTRA_WIDTH = 300;

    private static final int RECOLOR_DELAY_MS = 1_000;
    private static final boolean RECOLOR_TIMER_REPEATS = false;

    private static final int MIN_CANVAS_SIZE = 1;
    private static final int CANVAS_ORIGIN_X = 0;
    private static final int CANVAS_ORIGIN_Y = 0;

    private static final int INFO_TEXT_X = 10;
    private static final int INFO_TEXT_Y = 20;

    private static final int POINT_COUNTER_X = 10;
    private static final int POINT_COUNTER_Y = 80;

    private static final int RNG_LABEL_X = 10;
    private static final int RNG_LABEL_Y = 100;

    private static final int ERROR_TEXT_X = 10;
    private static final int ERROR_TEXT_Y = 120;

    private static final int INFO_FONT_SIZE = 12;
    private static final int POINT_COUNTER_FONT_SIZE = 48;
    private static final int RNG_LABEL_FONT_SIZE = 12;
    private static final int ERROR_FONT_SIZE = 12;

    private static final String FONT_SANS_SERIF = "SansSerif";
    private static final String FONT_MONOSPACED = "Monospaced";

    private static final Font INFO_FONT = new Font(FONT_SANS_SERIF, Font.PLAIN, INFO_FONT_SIZE);
    private static final Font POINT_COUNTER_FONT = new Font(FONT_SANS_SERIF, Font.BOLD, POINT_COUNTER_FONT_SIZE);
    private static final Font RNG_LABEL_FONT = new Font(FONT_SANS_SERIF, Font.BOLD, RNG_LABEL_FONT_SIZE);
    private static final Font ERROR_FONT = new Font(FONT_SANS_SERIF, Font.PLAIN, ERROR_FONT_SIZE);

    private static final Color DARK_BACKGROUND_COLOR = Color.BLACK;
    private static final Color LIGHT_BACKGROUND_COLOR = Color.WHITE;

    private static final Color DARK_INFO_COLOR = new Color(180, 200, 255);
    private static final Color LIGHT_INFO_COLOR = Color.BLUE;

    private static final Color DARK_COUNTER_COLOR = new Color(255, 100, 100);
    private static final Color LIGHT_COUNTER_COLOR = Color.RED;

    private static final Color DARK_QUANTUM_COLOR = new Color(100, 220, 100);
    private static final Color LIGHT_QUANTUM_COLOR = new Color(34, 139, 34);

    private static final Color DARK_PSEUDO_COLOR = new Color(255, 180, 60);
    private static final Color LIGHT_PSEUDO_COLOR = new Color(204, 120, 0);

    private static final Color DARK_ERROR_COLOR = new Color(255, 120, 120);
    private static final Color LIGHT_ERROR_COLOR = Color.RED;

    private static final String RNG_LABEL_QUANTUM_STATUS = "QUANTUM (API)";
    private static final String RNG_LABEL_PSEUDO_STATUS = "PSEUDO (Local)";

    private static final String RNG_MODE_LABEL_QUANTUM = "● QUANTUM";
    private static final String RNG_MODE_LABEL_PSEUDO = "● PSEUDO (L128X256MixRandom)";

    private static final String INFO_SEPARATOR = "  |  ";
    private static final String POINTS_LABEL = "Points: ";
    private static final String RANDOM_NUMBERS_LABEL = "Random numbers: ";

    private static final String ERROR_LOG_PREFIX = "Error: ";
    private static final String ANIMATION_STARTED_LOG_PREFIX = "Animation started: ";
    private static final String ANIMATION_STOPPED_LOG = "Animation stopped.";

    private static final int STACK_FONT_SIZE = 12;
    private static final int STACK_HEADER_FONT_SIZE = 11;

    private static final Font STACK_MONO_FONT = new Font(FONT_MONOSPACED, Font.PLAIN, STACK_FONT_SIZE);
    private static final Font STACK_HEADER_FONT = new Font(FONT_SANS_SERIF, Font.PLAIN, STACK_HEADER_FONT_SIZE);

    private static final Color STACK_ZEBRA_COLOR = new Color(245, 245, 242);
    private static final Color STACK_HEADER_COLOR = new Color(140, 140, 140);
    private static final Color STACK_SEPARATOR_COLOR = new Color(225, 225, 220);
    private static final Color STACK_NUMBER_COLOR = new Color(50, 50, 50);

    private static final int STACK_HEADER_HEIGHT = 20;
    private static final int STACK_HEADER_BASELINE_OFFSET = 4;
    private static final int STACK_VERTICAL_PADDING = 4;
    private static final int STACK_RIGHT_MARGIN = 40;
    private static final int STACK_MAX_CONSUMED_NUMBERS = 2_000;

    private static final int MAX_VISIBLE_DIGITS = 5;
    private static final int FIRST_DIGIT_BUCKET_INDEX = 0;
    private static final int DIGIT_INDEX_OFFSET = 1;

    private static final String DIGIT_HEADER_SUFFIX = "-digit";

    private static final int STACK_ROW_BASELINE_OFFSET = 4;
    private static final int STACK_ZEBRA_Y_OFFSET = 4;
    private static final int STACK_NUMBER_RIGHT_PADDING = 2;

    private static final int EVEN_ROW_DIVISOR = 2;
    private static final int EVEN_ROW_REMAINDER = 0;

    private static final String TRANSPARENT_FILE_SUFFIX = "_transparent.png";
    private static final String WHITE_FILE_SUFFIX = ".png";
    private static final String IMAGE_FORMAT_PNG = "PNG";

    private static final String SAVED_TRANSPARENT_LOG_PREFIX = "Saved (transparent): ";
    private static final String SAVED_WHITE_BACKGROUND_LOG_PREFIX = "Saved (white bg): ";
    private static final String FAILED_TRANSPARENT_SAVE_LOG_PREFIX = "Failed to save transparent image: ";
    private static final String FAILED_WHITE_BACKGROUND_SAVE_LOG_PREFIX = "Failed to save white-bg image: ";

    private static final Logger LOGGER = LoggerConfig.getLogger();

    private final VisualizationMode mode;
    private final RNProvider randomNumberProvider;
    private final String errorMessage;
    private BufferedImage offscreenImage;
    private boolean canvasInitialized = false;
    private final JLabel statusLabel;

    private Timer animationTimer;
    private volatile boolean isRunning = false;

    private final List<Point> pendingRecolorPoints = new ArrayList<>();
    private final Timer recolorTimer;

    public DotController(RNProvider randomNumberProvider, VisualizationMode mode, JLabel statusLabel) {
        this.statusLabel = statusLabel;
        this.mode = mode;
        this.randomNumberProvider = randomNumberProvider;

        int prefWidth = mode.usesDarkBackground()
                ? (int) (SIZE_WIDTH * Config.getDouble(CONFIG_WINDOW_SCALE_WIDTH))
                : SIZE_WIDTH + LIGHT_MODE_EXTRA_WIDTH;

        int prefHeight = mode.usesDarkBackground()
                ? (int) (SIZE_HEIGHT * Config.getDouble(CONFIG_WINDOW_SCALE_HEIGHT))
                : SIZE_HEIGHT;

        setPreferredSize(new Dimension(prefWidth, prefHeight));
        setBackground(mode.usesDarkBackground() ? DARK_BACKGROUND_COLOR : LIGHT_BACKGROUND_COLOR);

        errorMessage = null;

        initAnimationTimer();

        recolorTimer = new Timer(RECOLOR_DELAY_MS, e -> {
            synchronized (pendingRecolorPoints) {
                if (!pendingRecolorPoints.isEmpty()) {
                    var g2d = offscreenImage.createGraphics();
                    g2d.setColor(DARK_BACKGROUND_COLOR);

                    for (var p : pendingRecolorPoints) {
                        g2d.fillRect(p.x, p.y, DOT_SIZE, DOT_SIZE);
                    }

                    g2d.dispose();
                    pendingRecolorPoints.clear();
                    repaint();
                }
            }
        });

        recolorTimer.setRepeats(RECOLOR_TIMER_REPEATS);
    }

    private void initAnimationTimer() {
        animationTimer = new Timer(TIMER_DELAY, e -> {
            if (errorMessage == null) {
                var newPoints = mode.step(randomNumberProvider, offscreenImage, DOT_SIZE);

                repaint();

                if (mode.usesRecolorAnimation() && !newPoints.isEmpty()) {
                    synchronized (pendingRecolorPoints) {
                        pendingRecolorPoints.addAll(newPoints);
                    }

                    if (!recolorTimer.isRunning()) {
                        recolorTimer.restart();
                    }
                }
            } else {
                stop();
                repaint();
                LOGGER.severe(ERROR_LOG_PREFIX + errorMessage);
            }
        });
    }

    public void startDotMovement() {
        start();
    }

    public void start() {
        if (!isRunning && errorMessage == null) {
            animationTimer.start();
            isRunning = true;
            LOGGER.info(ANIMATION_STARTED_LOG_PREFIX + mode.getName());
        }
    }

    public void stop() {
        if (isRunning) {
            animationTimer.stop();
            isRunning = false;
            LOGGER.info(ANIMATION_STOPPED_LOG);
        }
    }

    public boolean toggle() {
        if (isRunning) {
            stop();
        } else {
            start();
        }

        return isRunning;
    }

    public boolean isRunning() {
        return isRunning;
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);

        if (!canvasInitialized || offscreenImage == null
                || offscreenImage.getWidth() != getWidth()
                || offscreenImage.getHeight() != getHeight()) {

            int w = Math.max(MIN_CANVAS_SIZE, getWidth());
            int h = Math.max(MIN_CANVAS_SIZE, getHeight());

            offscreenImage = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            mode.initialize(offscreenImage, w, h);
            canvasInitialized = true;
        }

        g.drawImage(offscreenImage, CANVAS_ORIGIN_X, CANVAS_ORIGIN_Y, null);

        Graphics2D g2d = (Graphics2D) g;
        g2d.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON
        );

        boolean dark = mode.usesDarkBackground();

        drawInfoText(g2d, dark);
        drawPointCounter(g2d, dark);
        drawRngModeIndicator(g2d, dark);
        drawErrorMessage(g2d, dark);

        if (!dark) {
            drawRandomNumbersStack(g);
        }
    }

    private void drawInfoText(Graphics2D g2d, boolean dark) {
        g2d.setFont(INFO_FONT);
        g2d.setColor(dark ? DARK_INFO_COLOR : LIGHT_INFO_COLOR);

        String rngName = randomNumberProvider.getMode() == RNProvider.Mode.QUANTUM
                ? RNG_LABEL_QUANTUM_STATUS
                : RNG_LABEL_PSEUDO_STATUS;

        String infoText = mode.getName()
                + INFO_SEPARATOR
                + POINTS_LABEL
                + mode.getPointCount()
                + INFO_SEPARATOR
                + RANDOM_NUMBERS_LABEL
                + rngName;

        g2d.drawString(infoText, INFO_TEXT_X, INFO_TEXT_Y);
    }

    private void drawPointCounter(Graphics2D g2d, boolean dark) {
        g2d.setFont(POINT_COUNTER_FONT);
        g2d.setColor(dark ? DARK_COUNTER_COLOR : LIGHT_COUNTER_COLOR);
        g2d.drawString(String.valueOf(mode.getPointCount()), POINT_COUNTER_X, POINT_COUNTER_Y);
    }

    private void drawRngModeIndicator(Graphics2D g2d, boolean dark) {
        var rngMode = randomNumberProvider.getMode();
        boolean isQuantum = rngMode == RNProvider.Mode.QUANTUM;

        g2d.setFont(RNG_LABEL_FONT);
        g2d.setColor(isQuantum
                ? (dark ? DARK_QUANTUM_COLOR : LIGHT_QUANTUM_COLOR)
                : (dark ? DARK_PSEUDO_COLOR : LIGHT_PSEUDO_COLOR));

        String modeLabel = isQuantum ? RNG_MODE_LABEL_QUANTUM : RNG_MODE_LABEL_PSEUDO;
        g2d.drawString(modeLabel, RNG_LABEL_X, RNG_LABEL_Y);
    }

    private void drawErrorMessage(Graphics2D g2d, boolean dark) {
        if (errorMessage == null) {
            return;
        }

        g2d.setColor(dark ? DARK_ERROR_COLOR : LIGHT_ERROR_COLOR);
        g2d.setFont(ERROR_FONT);
        g2d.drawString(errorMessage, ERROR_TEXT_X, ERROR_TEXT_Y);
    }

    private void drawRandomNumbersStack(Graphics g) {
        Graphics2D g2d = (Graphics2D) g;
        g2d.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON
        );

        int maxRows = (SIZE_HEIGHT - STACK_HEADER_HEIGHT - STACK_VERTICAL_PADDING) / ROW_HEIGHT;

        List<List<Long>> digitBuckets = new ArrayList<>();
        String[] headers = new String[MAX_COLUMNS];

        for (int i = 0; i < MAX_COLUMNS; i++) {
            digitBuckets.add(new ArrayList<>());
            headers[i] = (i + DIGIT_INDEX_OFFSET) + DIGIT_HEADER_SUFFIX;
        }

        for (Long randomValue : randomNumberProvider.getLastConsumedNumbers(STACK_MAX_CONSUMED_NUMBERS)) {
            int numDigits = String.valueOf(Math.abs(randomValue)).length();

            if (numDigits <= MAX_VISIBLE_DIGITS) {
                digitBuckets.get(numDigits - DIGIT_INDEX_OFFSET).add(randomValue);
            }
        }

        List<Integer> visibleColumns = new ArrayList<>();

        for (int i = FIRST_DIGIT_BUCKET_INDEX; i < MAX_COLUMNS; i++) {
            if (!digitBuckets.get(i).isEmpty()) {
                visibleColumns.add(i);
            }
        }

        if (visibleColumns.isEmpty()) {
            return;
        }

        int totalWidth = visibleColumns.size() * (COLUMN_WIDTH + COLUMN_SPACING) - COLUMN_SPACING;
        int startX = getWidth() - totalWidth - STACK_RIGHT_MARGIN;

        for (int visIdx = 0; visIdx < visibleColumns.size(); visIdx++) {
            int bucketIdx = visibleColumns.get(visIdx);
            List<Long> columnNumbers = digitBuckets.get(bucketIdx);
            int colX = startX + visIdx * (COLUMN_WIDTH + COLUMN_SPACING);

            drawStackHeader(g2d, headers[bucketIdx], colX);
            drawStackSeparator(g2d, colX);

            if (visIdx > 0) {
                drawColumnSeparator(g2d, colX);
            }

            drawStackNumbers(g2d, columnNumbers, colX, maxRows);
        }
    }

    private void drawStackHeader(Graphics2D g2d, String header, int colX) {
        g2d.setFont(STACK_HEADER_FONT);
        g2d.setColor(STACK_HEADER_COLOR);

        FontMetrics hfm = g2d.getFontMetrics();
        int headerTextWidth = hfm.stringWidth(header);

        g2d.drawString(
                header,
                colX + (COLUMN_WIDTH - headerTextWidth) / 2,
                STACK_HEADER_HEIGHT - STACK_HEADER_BASELINE_OFFSET
        );
    }

    private void drawStackSeparator(Graphics2D g2d, int colX) {
        g2d.setColor(STACK_SEPARATOR_COLOR);
        g2d.drawLine(colX, STACK_HEADER_HEIGHT, colX + COLUMN_WIDTH, STACK_HEADER_HEIGHT);
    }

    private void drawColumnSeparator(Graphics2D g2d, int colX) {
        int sepX = colX - COLUMN_SPACING / 2;

        g2d.setColor(STACK_SEPARATOR_COLOR);
        g2d.drawLine(sepX, CANVAS_ORIGIN_Y, sepX, SIZE_HEIGHT);
    }

    private void drawStackNumbers(Graphics2D g2d, List<Long> columnNumbers, int colX, int maxRows) {
        g2d.setFont(STACK_MONO_FONT);

        FontMetrics fm = g2d.getFontMetrics();
        int row = 0;

        for (int i = columnNumbers.size() - 1; i >= 0 && row < maxRows; i--, row++) {
            int y = SIZE_HEIGHT - (row * ROW_HEIGHT) - STACK_ROW_BASELINE_OFFSET;

            if (row % EVEN_ROW_DIVISOR == EVEN_ROW_REMAINDER) {
                g2d.setColor(STACK_ZEBRA_COLOR);
                g2d.fillRect(
                        colX,
                        y - ROW_HEIGHT + STACK_ZEBRA_Y_OFFSET,
                        COLUMN_WIDTH,
                        ROW_HEIGHT
                );
            }

            String text = columnNumbers.get(i).toString();
            int textWidth = fm.stringWidth(text);

            g2d.setColor(STACK_NUMBER_COLOR);
            g2d.drawString(
                    text,
                    colX + COLUMN_WIDTH - textWidth - STACK_NUMBER_RIGHT_PADDING,
                    y
            );
        }
    }

    public void updateStatusLabel(String message) {
        SwingUtilities.invokeLater(() -> statusLabel.setText(message));
    }

    public List<Long> getUsedRandomNumbers() {
        return randomNumberProvider.getConsumedNumbers();
    }

    public void shutdown() {
        stop();

        if (recolorTimer != null) {
            recolorTimer.stop();
        }
    }

    public int saveImages(java.io.File directory, String baseName) {
        int saved = 0;

        var transparentFile = new java.io.File(directory, baseName + TRANSPARENT_FILE_SUFFIX);

        try {
            javax.imageio.ImageIO.write(offscreenImage, IMAGE_FORMAT_PNG, transparentFile);
            LOGGER.info(SAVED_TRANSPARENT_LOG_PREFIX + transparentFile.getAbsolutePath());
            saved++;
        } catch (java.io.IOException e) {
            LOGGER.severe(FAILED_TRANSPARENT_SAVE_LOG_PREFIX + e.getMessage());
        }

        var whiteFile = new java.io.File(directory, baseName + WHITE_FILE_SUFFIX);

        try {
            var whiteImage = new BufferedImage(
                    offscreenImage.getWidth(),
                    offscreenImage.getHeight(),
                    BufferedImage.TYPE_INT_RGB
            );

            var g = whiteImage.createGraphics();

            g.setColor(Color.WHITE);
            g.fillRect(CANVAS_ORIGIN_X, CANVAS_ORIGIN_Y, whiteImage.getWidth(), whiteImage.getHeight());
            g.drawImage(offscreenImage, CANVAS_ORIGIN_X, CANVAS_ORIGIN_Y, null);
            g.dispose();

            javax.imageio.ImageIO.write(whiteImage, IMAGE_FORMAT_PNG, whiteFile);
            LOGGER.info(SAVED_WHITE_BACKGROUND_LOG_PREFIX + whiteFile.getAbsolutePath());
            saved++;
        } catch (java.io.IOException e) {
            LOGGER.severe(FAILED_WHITE_BACKGROUND_SAVE_LOG_PREFIX + e.getMessage());
        }

        return saved;
    }

}
