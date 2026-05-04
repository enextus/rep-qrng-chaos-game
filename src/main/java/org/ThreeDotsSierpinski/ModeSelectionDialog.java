package org.ThreeDotsSierpinski;

import javax.swing.*;
import java.awt.*;

/**
 * Диалог выбора режима визуализации.
 *
 * Показывается при запуске приложения. Отображает карточки
 * с описанием каждого доступного режима.
 */
public class ModeSelectionDialog {

    private static final String DIALOG_TITLE = "Quantum Random Visualizer";

    private VisualizationMode selectedMode = null;

    /**
     * Показывает диалог и ждёт выбора.
     *
     * @param parent родительский фрейм (может быть null)
     * @return выбранный режим, или null если закрыли без выбора
     */
    public VisualizationMode showAndWait(JFrame parent) {
        GraphicsConfiguration targetGraphicsConfiguration = parent == null
                ? null
                : parent.getGraphicsConfiguration();

        return showAndWait(parent, targetGraphicsConfiguration);
    }

    /**
     * Показывает диалог и центрирует его на указанном мониторе.
     *
     * @param parent                      родительский фрейм (может быть null)
     * @param targetGraphicsConfiguration целевой монитор/экран
     * @return выбранный режим, или null если закрыли без выбора
     */
    public VisualizationMode showAndWait(
            JFrame parent,
            GraphicsConfiguration targetGraphicsConfiguration
    ) {
        selectedMode = null;
        var modes = VisualizationMode.allModes();

        var dialog = new JDialog(parent, DIALOG_TITLE, true);
        dialog.setLayout(new BorderLayout(0, 0));
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        // Заголовок
        var header = new JPanel(new BorderLayout());
        header.setBorder(BorderFactory.createEmptyBorder(20, 24, 10, 24));
        header.setBackground(new Color(245, 245, 242));

        var title = new JLabel(DIALOG_TITLE);
        title.setFont(new Font("SansSerif", Font.BOLD, 20));
        header.add(title, BorderLayout.WEST);

        var subtitle = new JLabel("Выберите визуализацию");
        subtitle.setFont(new Font("SansSerif", Font.PLAIN, 13));
        subtitle.setForeground(new Color(120, 120, 120));
        header.add(subtitle, BorderLayout.SOUTH);

        dialog.add(header, BorderLayout.NORTH);

        // Карточки режимов
        var cardsPanel = new JPanel();
        cardsPanel.setLayout(new BoxLayout(cardsPanel, BoxLayout.Y_AXIS));
        cardsPanel.setBorder(BorderFactory.createEmptyBorder(10, 20, 20, 20));
        cardsPanel.setBackground(Color.WHITE);

        for (var mode : modes) {
            var card = createModeCard(mode, dialog);
            cardsPanel.add(card);
            cardsPanel.add(Box.createVerticalStrut(10));
        }

        var scrollPane = new JScrollPane(cardsPanel);
        scrollPane.setBorder(null);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        dialog.add(scrollPane, BorderLayout.CENTER);

        // Footer
        var footer = new JPanel(new FlowLayout(FlowLayout.CENTER));
        footer.setBorder(BorderFactory.createEmptyBorder(4, 0, 12, 0));
        footer.setBackground(new Color(245, 245, 242));
        var footerLabel = new JLabel("Powered by ANU Quantum Random Numbers API + L128X256MixRandom fallback");
        footerLabel.setFont(new Font("SansSerif", Font.PLAIN, 10));
        footerLabel.setForeground(new Color(160, 160, 160));
        footer.add(footerLabel);
        dialog.add(footer, BorderLayout.SOUTH);

        // Размер и позиционирование
        dialog.setSize(640, 180 + modes.length * 110);
        dialog.setMinimumSize(new Dimension(400, 300));
        centerDialog(dialog, parent, targetGraphicsConfiguration);
        dialog.setVisible(true); // Блокирует до закрытия (modal)

        return selectedMode;
    }

    private static void centerDialog(
            JDialog dialog,
            JFrame parent,
            GraphicsConfiguration targetGraphicsConfiguration
    ) {
        if (parent != null) {
            dialog.setLocationRelativeTo(parent);
            return;
        }

        if (targetGraphicsConfiguration == null) {
            dialog.setLocationRelativeTo(null);
            return;
        }

        Rectangle usableBounds = getUsableScreenBounds(targetGraphicsConfiguration);

        int x = usableBounds.x + Math.max(0, (usableBounds.width - dialog.getWidth()) / 2);
        int y = usableBounds.y + Math.max(0, (usableBounds.height - dialog.getHeight()) / 2);

        dialog.setLocation(x, y);
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

    /**
     * Создаёт карточку одного режима.
     */
    private JPanel createModeCard(VisualizationMode mode, JDialog dialog) {
        var card = new JPanel(new BorderLayout(12, 0));
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(220, 220, 215), 1, true),
                BorderFactory.createEmptyBorder(14, 16, 14, 16)
        ));
        card.setBackground(Color.WHITE);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 90));
        card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        // Иконка (символ)
        var icon = new JLabel(mode.getIcon());
        icon.setFont(new Font("SansSerif", Font.PLAIN, 32));
        icon.setPreferredSize(new Dimension(48, 48));
        icon.setHorizontalAlignment(SwingConstants.CENTER);
        card.add(icon, BorderLayout.WEST);

        // Текст
        var textPanel = new JPanel();
        textPanel.setLayout(new BoxLayout(textPanel, BoxLayout.Y_AXIS));
        textPanel.setOpaque(false);

        var name = new JLabel(mode.getName());
        name.setFont(new Font("SansSerif", Font.BOLD, 15));
        name.setAlignmentX(Component.LEFT_ALIGNMENT);
        textPanel.add(name);

        textPanel.add(Box.createVerticalStrut(4));

        // Описание (может содержать \n)
        for (String line : mode.getDescription().split("\n")) {
            var desc = new JLabel(line);
            desc.setFont(new Font("SansSerif", Font.PLAIN, 12));
            desc.setForeground(new Color(100, 100, 100));
            desc.setAlignmentX(Component.LEFT_ALIGNMENT);
            textPanel.add(desc);
        }

        card.add(textPanel, BorderLayout.CENTER);

        // Стрелка →
        var arrow = new JLabel("→");
        arrow.setFont(new Font("SansSerif", Font.PLAIN, 20));
        arrow.setForeground(new Color(180, 180, 180));
        card.add(arrow, BorderLayout.EAST);

        // Hover эффект
        card.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseEntered(java.awt.event.MouseEvent e) {
                card.setBackground(new Color(240, 245, 255));
                card.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(new Color(100, 140, 200), 1, true),
                        BorderFactory.createEmptyBorder(14, 16, 14, 16)
                ));
            }

            @Override
            public void mouseExited(java.awt.event.MouseEvent e) {
                card.setBackground(Color.WHITE);
                card.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(new Color(220, 220, 215), 1, true),
                        BorderFactory.createEmptyBorder(14, 16, 14, 16)
                ));
            }

            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                selectedMode = mode;
                dialog.dispose();
            }
        });

        return card;
    }
}
