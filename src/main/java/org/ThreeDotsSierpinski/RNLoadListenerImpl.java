package org.ThreeDotsSierpinski;

import javax.swing.*;

class RNLoadListenerImpl implements RNLoadListener {
    private final DotController controller;
    private final JFrame mainFrame;
    private final JToggleButton toggleSwitch;

    private final JTextArea rawDataTextArea;
    private JFrame rawDataFrame;
    private boolean quantumDataReceived = false;

    public RNLoadListenerImpl(DotController controller, JFrame mainFrame, JToggleButton toggleSwitch) {
        this.controller = controller;
        this.mainFrame = mainFrame;
        this.toggleSwitch = toggleSwitch;

        this.rawDataTextArea = new JTextArea();
        this.rawDataTextArea.setEditable(false);
    }

    private void showRawDataWindowIfNeeded() {
        if (rawDataFrame != null) return;
        rawDataFrame = new JFrame("Raw Data");
        rawDataFrame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        rawDataFrame.add(new JScrollPane(rawDataTextArea));
        int mainX = mainFrame.getX();
        int mainY = mainFrame.getY();
        int mainWidth = mainFrame.getWidth();
        int mainHeight = mainFrame.getHeight();
        int rawDataHeight = 150;
        int windowShadowOffset = 7;
        rawDataFrame.setSize(mainWidth, rawDataHeight);
        rawDataFrame.setLocation(mainX, mainY + mainHeight - windowShadowOffset);
        rawDataFrame.setVisible(true);
    }

    @Override
    public void onLoadingStarted() {
        SwingUtilities.invokeLater(() -> controller.updateStatusLabel("Loading data..."));
    }

    @Override
    public void onLoadingCompleted() {
        SwingUtilities.invokeLater(() -> controller.updateStatusLabel("Data loaded successfully."));
    }

    @Override
    public void onError(String errorMessage) {
        SwingUtilities.invokeLater(() -> controller.updateStatusLabel("Error: " + errorMessage));
    }

    @Override
    public void onRawDataReceived(String rawData) {
        quantumDataReceived = true;
        SwingUtilities.invokeLater(() -> {
            showRawDataWindowIfNeeded();
            rawDataTextArea.append(rawData + "\n");
        });
    }

    // Обработка ОБОИХ направлений переключения
    @Override
    public void onModeChanged(RNProvider.Mode mode) {
        SwingUtilities.invokeLater(() -> {
            // selected=true → QUANTUM, selected=false → PSEUDO
            toggleSwitch.setSelected(mode == RNProvider.Mode.QUANTUM);
        });
    }

    @Override
    public void onApiAvailabilityChanged(boolean isAvailable) {
        SwingUtilities.invokeLater(() -> {
            // Не трогаем enabled/disabled здесь для сетевых ошибок.
            // Enabled/disabled управляется ТОЛЬКО наличием API ключа (в App.launchMainWindow).
            // Этот callback только для обновления статусных сообщений.

            if (!isAvailable) {
                if (quantumDataReceived) {
                    controller.updateStatusLabel("API unavailable. Switched to PSEUDO (Local).");
                }
            } else {
                if (toggleSwitch.isSelected()) {
                    controller.updateStatusLabel("API connection restored.");
                }
            }
        });
    }
}
