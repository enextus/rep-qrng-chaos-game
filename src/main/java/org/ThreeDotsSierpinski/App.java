package org.ThreeDotsSierpinski;

import com.formdev.flatlaf.FlatLightLaf;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Главный класс приложения.
 * Запуск: диалог выбора режима → основное окно визуализации.
 */
public class App {
    private static final String LOG_APP_STARTED = "Application started.";
    private static final String LOG_GUI_STARTED = "GUI successfully launched.";
    private static final String LOG_APP_SHUTTING_DOWN = "Shutting down application.";
    private static final String LOG_WAITING_FOR_DATA = "Waiting for initial random numbers...";
    private static final String LOG_DATA_READY = "Initial data loaded, starting animation.";
    private static final String LOG_DATA_TIMEOUT = "Timeout waiting for initial data.";
    private static final String LOG_NO_MODE_SELECTED = "No mode selected, exiting.";
    private static final String LOG_SELECTED_MODE_PREFIX = "Selected mode: ";
    private static final String LOG_VISUALIZATION_FINISHED = "Visualization finished, returning to mode selection.";
    private static final String LOG_TARGET_SCREEN_BOUNDS_PREFIX = "Target screen bounds: ";
    private static final String LOG_RETURN_SCREEN_BOUNDS_PREFIX = "Return screen bounds: ";

    private static final String BUTTON_PLAY = "► Play";
    private static final String BUTTON_STOP = "Stop";
    private static final String BUTTON_FINISH_VISUALIZATION = "Закончить визуализацию";

    private static final int STATUS_PANEL_HORIZONTAL_GAP = 10;
    private static final int STATUS_PANEL_VERTICAL_GAP = 5;
    private static final int STATUS_LABEL_WIDTH = 250;
    private static final int STATUS_LABEL_HEIGHT = 20;
    private static final int RNG_FONT_SIZE = 11;
    private static final int RNG_SPACER_WIDTH = 5;
    private static final int TEST_BUTTON_WIDTH = 160;
    private static final int SAVE_BUTTON_WIDTH = 100;
    private static final int FINISH_BUTTON_WIDTH = 180;
    private static final int STATUS_BUTTON_HEIGHT = 28;
    private static final int INITIAL_DATA_TIMEOUT_MS = 15_000;

    private static final Logger LOGGER = LoggerConfig.getLogger();

    public static void main(String[] args) {
        FlatLightLaf.setup();

        LoggerConfig.initializeLogger();
        LOGGER.info(LOG_APP_STARTED);

        SwingUtilities.invokeLater(() -> {
            GraphicsConfiguration targetGraphicsConfiguration = resolveLaunchGraphicsConfiguration();
            LOGGER.info(LOG_TARGET_SCREEN_BOUNDS_PREFIX + targetGraphicsConfiguration.getBounds());
            showModeSelectionLoop(targetGraphicsConfiguration);
        });
    }

    private static void showModeSelectionLoop(GraphicsConfiguration targetGraphicsConfiguration) {
        var selector = new ModeSelectionDialog();
        var selectedMode = selector.showAndWait(null, targetGraphicsConfiguration);

        if (selectedMode == null) {
            LOGGER.info(LOG_NO_MODE_SELECTED);
            LOGGER.info(LOG_APP_SHUTTING_DOWN);
            System.exit(0);
            return;
        }

        LOGGER.info(LOG_SELECTED_MODE_PREFIX + selectedMode.getName());
        launchMainWindow(selectedMode, targetGraphicsConfiguration);
    }

    private static void launchMainWindow(
            VisualizationMode mode,
            GraphicsConfiguration targetGraphicsConfiguration
    ) {
        RNProvider randomNumberProvider = new RNProvider();
        JLabel statusLabel = new JLabel("Initializing...");
        AtomicBoolean visualizationFinished = new AtomicBoolean(false);

        String windowTitle = "Quantum Visualizer — " + mode.getName();
        var frame = new JFrame(windowTitle, targetGraphicsConfiguration);
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.setLayout(new BorderLayout());

        int basePanelWidth = Config.getInt("panel.size.width");
        int basePanelHeight = Config.getInt("panel.size.height");
        double scaleWidth = Config.getDouble("window.scale.width");
        double scaleHeight = Config.getDouble("window.scale.height");

        int finalWidth = (int) Math.round(basePanelWidth * scaleWidth);
        int finalHeight = (int) Math.round(basePanelHeight * scaleHeight);

        var dotController = new DotController(randomNumberProvider, mode, statusLabel);
        frame.add(dotController, BorderLayout.CENTER);

        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                finishVisualization(frame, dotController, visualizationFinished);
            }
        });

        // Панель статуса
        var statusPanel = new JPanel(new FlowLayout(
                FlowLayout.LEFT,
                STATUS_PANEL_HORIZONTAL_GAP,
                STATUS_PANEL_VERTICAL_GAP
        ));

        statusLabel.setPreferredSize(new Dimension(STATUS_LABEL_WIDTH, STATUS_LABEL_HEIGHT));
        statusPanel.add(statusLabel);

        var playStopButton = new JButton(BUTTON_PLAY);
        playStopButton.setEnabled(false);
        statusPanel.add(playStopButton);

// === TOGGLE: selected=true → QUANTUM, selected=false → PSEUDO ===
        var rngLabel = new JLabel("...");
        rngLabel.setFont(new Font("SansSerif", Font.PLAIN, RNG_FONT_SIZE));
        statusPanel.add(rngLabel);

        statusPanel.add(Box.createHorizontalStrut(RNG_SPACER_WIDTH));

        var rngToggle = new JToggleButton("RNG");
        rngToggle.putClientProperty("JToggleButton.buttonType", "toggle");
        rngToggle.setSelected(false);
        rngToggle.setEnabled(false); // Неактивна до загрузки данных
        statusPanel.add(rngToggle);

// Добавляем tooltip для ясности
        rngToggle.setToolTipText("Toggle between QUANTUM and PSEUDO random number sources");

// Синхронизация label с toggle
        Runnable syncToggleLabel = () -> {
            if (rngToggle.isSelected()) {
                rngLabel.setText("QUANTUM (API)");
                rngToggle.setText("QUANTUM");
            } else {
                rngLabel.setText("PSEUDO (Local)");
                rngToggle.setText("PSEUDO");
            }
        };

        rngToggle.addChangeListener(e -> syncToggleLabel.run());
        syncToggleLabel.run();

        var testButton = new JButton("Проверить качество");
        testButton.setPreferredSize(new Dimension(TEST_BUTTON_WIDTH, STATUS_BUTTON_HEIGHT));
        statusPanel.add(testButton);

        var saveButton = new JButton("Save PNG");
        saveButton.setPreferredSize(new Dimension(SAVE_BUTTON_WIDTH, STATUS_BUTTON_HEIGHT));
        statusPanel.add(saveButton);

        var finishButton = new JButton(BUTTON_FINISH_VISUALIZATION);
        finishButton.setPreferredSize(new Dimension(FINISH_BUTTON_WIDTH, STATUS_BUTTON_HEIGHT));
        statusPanel.add(finishButton);

        frame.add(statusPanel, BorderLayout.SOUTH);

        frame.setSize(finalWidth, finalHeight);
        centerWindowOnGraphicsConfiguration(frame, targetGraphicsConfiguration);
        frame.setVisible(true);
        LOGGER.info(LOG_GUI_STARTED);

        randomNumberProvider.addDataLoadListener(new RNLoadListenerImpl(dotController, frame, rngToggle));

// Play/Stop
        playStopButton.addActionListener(_ -> {
            boolean running = dotController.toggle();
            playStopButton.setText(running ? BUTTON_STOP : BUTTON_PLAY);
            if (running) {
                // FIX: показываем режим при рисовании
                boolean isQuantum = rngToggle.isSelected() && rngToggle.isEnabled();
                statusLabel.setText(isQuantum ? "Drawing... (Quantum)" : "Drawing... (Pseudo-random)");
            } else {
                statusLabel.setText("Paused. Points: " + dotController.getUsedRandomNumbers().size());
            }
        });

// FIX: selected=true → QUANTUM, selected=false → PSEUDO
        rngToggle.addActionListener(_ -> {
            // Если toggle disabled (нет API ключа), показываем диалог
            if (!rngToggle.isEnabled()) {
                JOptionPane.showMessageDialog(
                        frame,
                        "API key not configured.\n\nQuantum random numbers require a valid API key.\n" +
                                "Set QRNG_API_KEY environment variable or add it to .env file.",
                        "API Key Required",
                        JOptionPane.WARNING_MESSAGE
                );
                rngToggle.setSelected(false);
                syncToggleLabel.run();
                return;
            }

            boolean wantsQuantum = rngToggle.isSelected();

            // FIX: Проверяем rate limit перед переключением
            String currentReason = randomNumberProvider.getFallbackReason();
            boolean isRateLimit = currentReason != null &&
                    (currentReason.contains("429") || currentReason.contains("limit") ||
                            currentReason.contains("Limit Exceeded") || currentReason.contains("лимит"));

            if (wantsQuantum && isRateLimit) {
                // Пытаемся переключиться на QUANTUM, но rate limit активен
                JOptionPane.showMessageDialog(
                        frame,
                        "Daily API limit exceeded.\n\nQuantum random numbers are temporarily unavailable.\n" +
                                "Please try again later or continue using PSEUDO mode.",
                        "Rate Limit Exceeded",
                        JOptionPane.WARNING_MESSAGE
                );
                // Возвращаем toggle в PSEUDO
                rngToggle.setSelected(false);
                syncToggleLabel.run();
                return;
            }

            randomNumberProvider.setForcedPseudo(!wantsQuantum);

            if (dotController.isRunning()) {
                dotController.updateStatusLabel(wantsQuantum
                        ? "Drawing... (Quantum)"
                        : "Drawing... (Pseudo-random)");
            }
        });

        // Проверить качество
        testButton.addActionListener(_ -> {
            List<Long> numbers = dotController.getUsedRandomNumbers();
            if (numbers.size() < 10) {
                statusLabel.setText("Нужно минимум 10 точек для тестов");
                return;
            }

            RandomnessTestSuite suite = new RandomnessTestSuite();
            List<TestResult> results = suite.runAll(numbers, 0.05);
            long passed = results.stream().filter(TestResult::passed).count();

            statusLabel.setText("Тесты: " + passed + "/" + results.size()
                    + " пройдено (" + numbers.size() + " чисел)");

            var panel = new JPanel();
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
            panel.setBorder(BorderFactory.createEmptyBorder(8, 4, 8, 4));

            for (TestResult result : results) {
                panel.add(getJPanel(result));
            }

            panel.add(Box.createVerticalStrut(8));
            var summary = new JLabel("Итого: " + passed + "/" + results.size() + " тестов пройдено");
            summary.setFont(new Font("SansSerif", Font.BOLD, 13));
            summary.setAlignmentX(Component.LEFT_ALIGNMENT);
            summary.setBorder(BorderFactory.createEmptyBorder(4, 8, 0, 0));
            panel.add(summary);

            var legend = new JLabel("<html><font color='#228B22'>● отлично</font>"
                    + "   <font color='#CC9900'>● приемлемо</font>"
                    + "   <font color='#CC0000'>● не пройден</font></html>");
            legend.setFont(new Font("SansSerif", Font.PLAIN, 11));
            legend.setBorder(BorderFactory.createEmptyBorder(6, 8, 0, 0));
            legend.setAlignmentX(Component.LEFT_ALIGNMENT);
            panel.add(legend);

            JOptionPane.showMessageDialog(
                    frame, panel,
                    "Результаты тестов случайности (" + numbers.size() + " чисел)",
                    passed == results.size() ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE
            );
        });

        // Save PNG
        saveButton.addActionListener(_ -> {
            int points = mode.getPointCount();
            var timestamp = java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
            var baseName = mode.getId() + "_" + timestamp + "_" + points + "pts";

            var dirChooser = new JFileChooser();
            dirChooser.setDialogTitle("Выберите папку для сохранения");
            dirChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            dirChooser.setAcceptAllFileFilterUsed(false);

            if (dirChooser.showSaveDialog(frame) == JFileChooser.APPROVE_OPTION) {
                var directory = dirChooser.getSelectedFile();
                int saved = dotController.saveImages(directory, baseName);
                statusLabel.setText("Saved " + saved + "/2 files → " + directory.getName() + "/");
            }
        });

        // Закончить визуализацию → вернуться к выбору режима
        finishButton.addActionListener(_ ->
                finishVisualization(frame, dotController, visualizationFinished)
        );

        // Ожидание инициализации
        Thread.startVirtualThread(() -> {
            LOGGER.info(LOG_WAITING_FOR_DATA);
            SwingUtilities.invokeLater(() -> {
                if (!visualizationFinished.get() && frame.isDisplayable()) {
                    statusLabel.setText("Connecting to API...");
                }
            });

            boolean dataReady = randomNumberProvider.waitForInitialData(INITIAL_DATA_TIMEOUT_MS);

            SwingUtilities.invokeLater(() -> {
                if (visualizationFinished.get() || !frame.isDisplayable()) {
                    return;
                }

                if (dataReady) {
                    LOGGER.info(LOG_DATA_READY);
                    var rngMode = randomNumberProvider.getMode();

                    rngToggle.setEnabled(randomNumberProvider.isApiKeyConfigured());
                    rngToggle.setSelected(rngMode == RNProvider.Mode.QUANTUM);
                    syncToggleLabel.run();

                    if (rngMode == RNProvider.Mode.PSEUDO) {
                        String reason = randomNumberProvider.getFallbackReason();
                        String displayReason;

                        if (reason == null) {
                            displayReason = "fallback";
                        } else if (reason.contains("API key") || reason.contains("key")) {
                            displayReason = "no API key";
                        } else if (reason.contains("429") || reason.contains("limit") ||
                                reason.contains("Limit Exceeded") || reason.contains("лимит")) {
                            displayReason = "rate limit";
                        } else if (reason.contains("Connection refused") || reason.contains("timed out")
                                || reason.contains("UnknownHost") || reason.contains("unavailable")) {
                            displayReason = "API unavailable";
                        } else if (reason.contains("forced") || reason.contains("Forced") ||
                                reason.contains("Default local")) {
                            displayReason = "manual";
                        } else {
                            displayReason = reason;
                        }

                        statusLabel.setText("PSEUDO mode (" + displayReason + ")");
                    } else {
                        statusLabel.setText("Ready. (QUANTUM)");
                    }
                } else {
                    LOGGER.warning(LOG_DATA_TIMEOUT);
                    String error = randomNumberProvider.getLastError();

                    rngToggle.setEnabled(randomNumberProvider.isApiKeyConfigured());
                    rngToggle.setSelected(false);
                    syncToggleLabel.run();

                    if (!randomNumberProvider.isApiKeyConfigured()) {
                        statusLabel.setText("PSEUDO mode (no API key)");
                    } else {
                        statusLabel.setText("PSEUDO mode (API unavailable)");
                    }
                }

                playStopButton.setEnabled(true);
            });
        });
    }

    private static void finishVisualization(
            JFrame frame,
            DotController dotController,
            AtomicBoolean visualizationFinished
    ) {
        if (!visualizationFinished.compareAndSet(false, true)) {
            return;
        }

        GraphicsConfiguration returnGraphicsConfiguration = resolveWindowGraphicsConfiguration(frame);

        dotController.shutdown();
        frame.dispose();

        LOGGER.info(LOG_VISUALIZATION_FINISHED);
        LOGGER.info(LOG_RETURN_SCREEN_BOUNDS_PREFIX + returnGraphicsConfiguration.getBounds());

        SwingUtilities.invokeLater(() -> showModeSelectionLoop(returnGraphicsConfiguration));
    }


    /**
     * Возвращает монитор, на котором фактически находилось окно визуализации
     * в момент завершения. Это важно для multi-monitor workflow: если пользователь
     * перетащил окно модуса на другой монитор и нажал «Закончить визуализацию»,
     * следующий ModeSelectionDialog должен открыться именно там.
     */
    private static GraphicsConfiguration resolveWindowGraphicsConfiguration(Window window) {
        if (window == null) {
            return getDefaultGraphicsConfiguration();
        }

        Rectangle windowBounds = window.getBounds();

        if (windowBounds != null && !windowBounds.isEmpty()) {
            GraphicsConfiguration largestIntersectionGraphicsConfiguration =
                    findGraphicsConfigurationWithLargestIntersection(windowBounds);
            if (largestIntersectionGraphicsConfiguration != null) {
                return largestIntersectionGraphicsConfiguration;
            }

            Point windowCenter = new Point(
                    windowBounds.x + windowBounds.width / 2,
                    windowBounds.y + windowBounds.height / 2
            );

            GraphicsConfiguration centerGraphicsConfiguration = findGraphicsConfiguration(windowCenter);
            if (centerGraphicsConfiguration != null) {
                return centerGraphicsConfiguration;
            }
        }

        GraphicsConfiguration currentGraphicsConfiguration = window.getGraphicsConfiguration();
        if (currentGraphicsConfiguration != null) {
            return currentGraphicsConfiguration;
        }

        return getDefaultGraphicsConfiguration();
    }

    private static GraphicsConfiguration findGraphicsConfigurationWithLargestIntersection(Rectangle windowBounds) {
        GraphicsEnvironment graphicsEnvironment = GraphicsEnvironment.getLocalGraphicsEnvironment();

        GraphicsConfiguration bestGraphicsConfiguration = null;
        long bestIntersectionArea = 0;

        for (GraphicsDevice screenDevice : graphicsEnvironment.getScreenDevices()) {
            GraphicsConfiguration graphicsConfiguration = screenDevice.getDefaultConfiguration();
            Rectangle intersection = graphicsConfiguration.getBounds().intersection(windowBounds);

            long intersectionArea = (long) Math.max(0, intersection.width)
                    * Math.max(0, intersection.height);

            if (intersectionArea > bestIntersectionArea) {
                bestIntersectionArea = intersectionArea;
                bestGraphicsConfiguration = graphicsConfiguration;
            }
        }

        return bestGraphicsConfiguration;
    }

    private static GraphicsConfiguration getDefaultGraphicsConfiguration() {
        return GraphicsEnvironment
                .getLocalGraphicsEnvironment()
                .getDefaultScreenDevice()
                .getDefaultConfiguration();
    }

    /**
     * Java/Swing не знает, из какого внешнего окна Windows был запущен процесс
     * (IDEA, VS Code, Terminal). Поэтому выбираем монитор под курсором мыши в момент старта.
     * Обычно курсор находится именно на том мониторе, где пользователь нажал Run / запустил команду.
     */
    private static GraphicsConfiguration resolveLaunchGraphicsConfiguration() {
        try {
            PointerInfo pointerInfo = MouseInfo.getPointerInfo();
            if (pointerInfo != null) {
                Point pointerLocation = pointerInfo.getLocation();
                GraphicsConfiguration pointerGraphicsConfiguration = findGraphicsConfiguration(pointerLocation);
                if (pointerGraphicsConfiguration != null) {
                    return pointerGraphicsConfiguration;
                }
            }
        } catch (HeadlessException e) {
            LOGGER.warning("Cannot resolve pointer screen in headless environment: " + e.getMessage());
        }

        return getDefaultGraphicsConfiguration();
    }

    private static GraphicsConfiguration findGraphicsConfiguration(Point point) {
        GraphicsEnvironment graphicsEnvironment = GraphicsEnvironment.getLocalGraphicsEnvironment();

        for (GraphicsDevice screenDevice : graphicsEnvironment.getScreenDevices()) {
            GraphicsConfiguration graphicsConfiguration = screenDevice.getDefaultConfiguration();
            if (graphicsConfiguration.getBounds().contains(point)) {
                return graphicsConfiguration;
            }
        }

        return null;
    }

    private static void centerWindowOnGraphicsConfiguration(
            Window window,
            GraphicsConfiguration graphicsConfiguration
    ) {
        if (graphicsConfiguration == null) {
            window.setLocationRelativeTo(null);
            return;
        }

        Rectangle usableBounds = getUsableScreenBounds(graphicsConfiguration);

        int x = usableBounds.x + Math.max(0, (usableBounds.width - window.getWidth()) / 2);
        int y = usableBounds.y + Math.max(0, (usableBounds.height - window.getHeight()) / 2);

        window.setLocation(x, y);
    }

    private static Rectangle getUsableScreenBounds(GraphicsConfiguration graphicsConfiguration) {
        Rectangle bounds = graphicsConfiguration.getBounds();
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(graphicsConfiguration);

        return new Rectangle(
                bounds.x + insets.left,
                bounds.y + insets.top,
                bounds.width - insets.left - insets.right,
                bounds.height - insets.top - insets.bottom
        );
    }

    private static JPanel getJPanel(TestResult result) {
        var row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        var indicator = new JLabel("●");
        indicator.setFont(new Font("SansSerif", Font.BOLD, 16));
        indicator.setForeground(switch (result.quality()) {
            case STRONG -> new Color(34, 139, 34);
            case MARGINAL -> new Color(204, 153, 0);
            case FAIL -> new Color(204, 0, 0);
        });
        row.add(indicator);
        var mark = new JLabel(switch (result.quality()) {
            case STRONG -> "✓";
            case MARGINAL -> "○";
            case FAIL -> "✗";
        });
        mark.setFont(new Font("SansSerif", Font.BOLD, 14));
        mark.setForeground(indicator.getForeground());
        row.add(mark);
        var text = new JLabel(result.statistic() + "    " + result.testName());
        text.setFont(new Font("Monospaced", Font.PLAIN, 13));
        row.add(text);
        return row;
    }
}
