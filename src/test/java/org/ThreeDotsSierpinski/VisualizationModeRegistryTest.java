package org.ThreeDotsSierpinski;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("VisualizationMode registry")
@Tag("fast")
class VisualizationModeRegistryTest {

    @Test
    @DisplayName("Registers all current visualization modes")
    void registersAllCurrentVisualizationModes() {
        Set<String> ids = Arrays.stream(VisualizationMode.allModes())
                .map(VisualizationMode::getId)
                .collect(Collectors.toSet());

        assertTrue(ids.contains("Sierpinski"));
        assertTrue(ids.contains("dla"));
        assertTrue(ids.contains("voronoi"));
        assertTrue(ids.contains("barnsley-fern"));
        assertTrue(ids.contains("random-walk-heatmap"));
        assertTrue(ids.contains("galton-board"));
        assertTrue(ids.contains("percolation"));
    }

    @Test
    @DisplayName("Mode ids are unique")
    void modeIdsAreUnique() {
        VisualizationMode[] modes = VisualizationMode.allModes();
        long uniqueIds = Arrays.stream(modes)
                .map(VisualizationMode::getId)
                .distinct()
                .count();

        assertEquals(modes.length, uniqueIds);
    }
}
