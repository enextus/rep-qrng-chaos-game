package org.ThreeDotsSierpinski;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.List;
import javax.swing.*;

/**
 * Интерфейс для режимов визуализации случайных чисел.
 * Каждый режим:
 * - Получает случайные числа из RNProvider
 * - Рисует на BufferedImage
 * - Возвращает список нарисованных точек (для анимации RED→BLACK)
 * Для добавления нового режима:
 * 1. Создать класс, реализующий этот интерфейс
 * 2. Зарегистрировать в {@link VisualizationMode#allModes()}
 */
public interface VisualizationMode {

    /** Уникальный идентификатор режима (для конфига) */
    String getId();

    /** Человекочитаемое название */
    String getName();

    /** Краткое описание (1-2 строки) */
    String getDescription();

    /** Эмодзи или символ для карточки выбора */
    String getIcon();

    /**
     * Инициализация. Вызывается один раз перед началом анимации.
     *
     * @param canvas    изображение для рисования
     * @param width     ширина области
     * @param height    высота области
     */
    void initialize(BufferedImage canvas, int width, int height);

    /**
     * Один шаг анимации. Потребляет случайные числа, рисует на canvas.
     * Вызывается из EDT (Swing Timer) — безопасен для Swing.
     *
     * @param provider источник случайных чисел
     * @param canvas   изображение для рисования
     * @param dotSize  размер точки (из конфига)
     * @return список точек, нарисованных красным (для последующей перекраски в чёрный)
     */
    List<Point> step(RNProvider provider, BufferedImage canvas, int dotSize);

    /** Количество нарисованных точек с момента initialize() */
    int getPointCount();

    /** Количество потреблённых случайных чисел */
    int getRandomNumbersUsed();

    /**
     * Полная перерисовка текущего состояния без потребления новых случайных чисел.
     * Используется mode-specific UI controls, например для скрытия/показа
     * дополнительных визуальных слоёв.
     */
    default void redraw(BufferedImage canvas, int width, int height, int dotSize) {
        // Default no-op: режимы без собственного состояния не обязаны поддерживать redraw.
    }

    /**
     * Дополнительные UI-контролы, специфичные для конкретного режима визуализации.
     * Например, VoronoiMode может добавить переключатель показа меток центров.
     */
    default List<JComponent> createModeControls(DotController controller) {
        return List.of();
    }

    /**
     * Нужна ли анимация RED→BLACK для новых точек?
     * True = Sierpinski-style (точки сначала красные, через 1с чёрные).
     * False = режим сам управляет цветами (DLA, Percolation и т.д.).
     */
    default boolean usesRecolorAnimation() { return true; }

    /**
     * Нужен ли чёрный фон? (DLA — да, Sierpinski — нет)
     */
    default boolean usesDarkBackground() { return false; }

    /**
     * Реестр всех доступных режимов.
     * Для добавления нового — просто добавить в массив.
     */
    static VisualizationMode[] allModes() {
        return new VisualizationMode[] {
                new SierpinskiMode(),
                new DLAMode(),
                new VoronoiMode(),
                // Добавьте новые режимы здесь:
                // new PercolationMode(),
                // new BlueNoiseMode(),
                // new VoronoiMode(),
        };
    }
}
