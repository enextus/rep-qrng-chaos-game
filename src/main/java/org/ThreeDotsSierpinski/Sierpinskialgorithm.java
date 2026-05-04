package org.ThreeDotsSierpinski;

import java.awt.Point;

/**
 * Алгоритм построения фрактала Серпинского методом "Chaos Game".
 * Правила:
 * 1. Начинаем с произвольной точки внутри треугольника
 * 2. По случайному числу выбираем одну из трёх вершин (modulo 3)
 * 3. Перемещаемся на половину расстояния к выбранной вершине
 * 4. Повторяем шаги 2-3
 * Этот класс содержит только математику — никаких зависимостей от Swing/UI.
 */
public class SierpinskiAlgorithm {

    private final Point vertexA; // Верхняя вершина
    private final Point vertexB; // Нижняя левая
    private final Point vertexC; // Нижняя правая

    /**
     * Создаёт алгоритм с заданными параметрами треугольника.
     *
     * @param width  ширина области рисования
     * @param height высота области рисования
     */
    public SierpinskiAlgorithm(int width, int height) {
        this.vertexA = new Point(width / 2, 0);
        this.vertexB = new Point(0, height);
        this.vertexC = new Point(width, height);
    }

    /**
     * Создаёт алгоритм с параметрами из конфигурации.
     */
    public SierpinskiAlgorithm() {
        this(
                Config.getInt("panel.size.width"),
                Config.getInt("panel.size.height")
        );
    }

    /**
     * Вычисляет новую позицию точки по алгоритму Chaos Game.
     * Использует modulo 3 для абсолютно равномерного выбора вершины,
     * независимо от диапазона случайных чисел.
     *
     * @param currentPoint текущая позиция точки
     * @param randomValue  случайное число для выбора вершины
     * @return новая позиция точки (середина отрезка к выбранной вершине)
     */
    public Point calculateNewDotPosition(Point currentPoint, long randomValue) {
        int vertexIndex = (int) (Math.abs(randomValue) % 3);

        Point target = switch (vertexIndex) {
            case 0 -> vertexA;
            case 1 -> vertexB;
            default -> vertexC;
        };

        return new Point(
                (currentPoint.x + target.x) / 2,
                (currentPoint.y + target.y) / 2
        );
    }

    public Point getVertexA() { return new Point(vertexA); }
    public Point getVertexB() { return new Point(vertexB); }
    public Point getVertexC() { return new Point(vertexC); }
}
