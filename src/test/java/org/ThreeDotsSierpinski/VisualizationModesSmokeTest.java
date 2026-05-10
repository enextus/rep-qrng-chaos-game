package org.ThreeDotsSierpinski;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("Visualization modes smoke tests")
@Tag("fast")
class VisualizationModesSmokeTest {

    private static final int CANVAS_WIDTH = 180;
    private static final int CANVAS_HEIGHT = 160;
    private static final int DOT_SIZE = 2;

    @Test
    @DisplayName("BarnsleyFernMode consumes numbers and draws points")
    void barnsleyFernConsumesNumbersAndDrawsPoints() {
        BarnsleyFernMode mode = new BarnsleyFernMode();
        BufferedImage canvas = newCanvas();
        mode.initialize(canvas, CANVAS_WIDTH, CANVAS_HEIGHT);

        List<Point> points = mode.step(sequentialProvider(), canvas, DOT_SIZE);

        assertFalse(points.isEmpty());
        assertEquals(points.size(), mode.getPointCount());
        assertEquals(mode.getPointCount(), mode.getRandomNumbersUsed());
        assertTrue(mode.usesDarkBackground());
        assertFalse(mode.usesRecolorAnimation());
    }

    @Test
    @DisplayName("RandomWalkHeatmapMode consumes random directions")
    void randomWalkHeatmapConsumesDirections() {
        RandomWalkHeatmapMode mode = new RandomWalkHeatmapMode();
        BufferedImage canvas = newCanvas();
        mode.initialize(canvas, CANVAS_WIDTH, CANVAS_HEIGHT);

        mode.step(sequentialProvider(), canvas, DOT_SIZE);

        assertTrue(mode.getPointCount() > 0);
        assertEquals(mode.getPointCount(), mode.getRandomNumbersUsed());
        assertTrue(mode.usesDarkBackground());
        assertFalse(mode.usesRecolorAnimation());
    }

    @Test
    @DisplayName("GaltonBoardMode animates active balls and eventually completes balls")
    void galtonBoardEventuallyCompletesBalls() {
        GaltonBoardMode mode = new GaltonBoardMode();
        BufferedImage canvas = newCanvas();
        mode.initialize(canvas, CANVAS_WIDTH, CANVAS_HEIGHT);
        RNProvider provider = sequentialProvider();

        for (int i = 0; i < 40; i++) {
            mode.step(provider, canvas, DOT_SIZE);
        }

        assertTrue(mode.getPointCount() > 0);
        assertTrue(mode.getRandomNumbersUsed() > mode.getPointCount());
        assertTrue(mode.usesDarkBackground());
        assertFalse(mode.usesRecolorAnimation());
    }

    @Test
    @DisplayName("VoronoiMode exposes marker controls and refreshes on toggle")
    void voronoiMarkerToggleRefreshesController() {
        VoronoiMode mode = new VoronoiMode();
        DotController controller = mock(DotController.class);

        List<JComponent> controls = mode.createModeControls(controller);

        assertFalse(controls.isEmpty());
        JToggleButton toggle = controls.stream()
                .filter(JToggleButton.class::isInstance)
                .map(JToggleButton.class::cast)
                .findFirst()
                .orElseThrow();

        toggle.doClick();

        verify(controller).refreshVisualization();
    }

    @Test
    @DisplayName("VoronoiMode step and redraw work with stored seeds")
    void voronoiStepAndRedrawWork() {
        VoronoiMode mode = new VoronoiMode();
        BufferedImage canvas = newCanvas();
        mode.initialize(canvas, CANVAS_WIDTH, CANVAS_HEIGHT);

        List<Point> points = mode.step(sequentialProvider(), canvas, DOT_SIZE);
        assertFalse(points.isEmpty());
        assertTrue(mode.getPointCount() > 0);
        assertTrue(mode.getRandomNumbersUsed() > 0);

        assertDoesNotThrow(() -> mode.redraw(canvas, CANVAS_WIDTH, CANVAS_HEIGHT, DOT_SIZE));
    }

    @Test
    @DisplayName("PercolationMode consumes cell decisions and supports redraw")
    void percolationConsumesDecisionsAndRedraws() {
        PercolationMode mode = new PercolationMode();
        BufferedImage canvas = newCanvas();
        mode.initialize(canvas, CANVAS_WIDTH, CANVAS_HEIGHT);

        mode.step(sequentialProvider(), canvas, DOT_SIZE);

        assertTrue(mode.getPointCount() > 0);
        assertEquals(mode.getPointCount(), mode.getRandomNumbersUsed());
        assertTrue(mode.usesDarkBackground());
        assertFalse(mode.usesRecolorAnimation());
        assertDoesNotThrow(() -> mode.redraw(canvas, CANVAS_WIDTH, CANVAS_HEIGHT, DOT_SIZE));
    }

    @Test
    @DisplayName("Modes handle temporarily empty provider without crashing")
    void modesHandleEmptyProviderWithoutCrashing() {
        RNProvider emptyProvider = mock(RNProvider.class);
        when(emptyProvider.getNextRandomNumber()).thenReturn(OptionalInt.empty());

        List<VisualizationMode> modes = List.of(
                new BarnsleyFernMode(),
                new RandomWalkHeatmapMode(),
                new GaltonBoardMode(),
                new PercolationMode()
        );

        for (VisualizationMode mode : modes) {
            BufferedImage canvas = newCanvas();
            mode.initialize(canvas, CANVAS_WIDTH, CANVAS_HEIGHT);
            assertDoesNotThrow(() -> mode.step(emptyProvider, canvas, DOT_SIZE), mode.getName());
            assertEquals(0, mode.getRandomNumbersUsed(), mode.getName());
        }
    }

    private static BufferedImage newCanvas() {
        return new BufferedImage(CANVAS_WIDTH, CANVAS_HEIGHT, BufferedImage.TYPE_INT_ARGB);
    }

    private static RNProvider sequentialProvider() {
        RNProvider provider = mock(RNProvider.class);
        AtomicInteger value = new AtomicInteger();
        when(provider.getNextRandomNumber()).thenAnswer(_ -> OptionalInt.of(value.getAndIncrement()));
        return provider;
    }
}
