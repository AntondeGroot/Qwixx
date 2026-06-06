package nl.adg.qwixx.game;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import nl.adg.qwixx.data.Cell;
import nl.adg.qwixx.data.CellTag;
import nl.adg.qwixx.data.Row;
import org.junit.jupiter.api.Test;

class ConnectedCellsFactoryTest {

    @Test
    void places12AutoCrossTaggedCellsInStandardMode() {
        List<Row> rows = buildRows(BaseVariant.STANDARD, new Random(0));

        long taggedCount = rows.stream()
                .flatMap(r -> r.cells().stream())
                .filter(c -> c.tags().stream().anyMatch(t -> t instanceof CellTag.AutoCross))
                .count();

        // 3 pairs × 2 connections × 2 cells (bidirectional) = 12
        assertEquals(12, taggedCount, "3 pairs × 2 bidirectional connections = 12 tagged cells");
    }

    @Test
    void noConnectionsOnForbiddenPositions() {
        for (int seed = 0; seed < 30; seed++) {
            List<Row> rows = buildRows(BaseVariant.STANDARD, new Random(seed));
            for (Row row : rows) {
                for (Cell cell : row.cells()) {
                    if (cell.tags().stream().anyMatch(t -> t instanceof CellTag.AutoCross)) {
                        assertNotEquals(0, cell.position(),
                                "position 0 must not be a connection (seed=" + seed + ")");
                        assertFalse(cell.isClosingEligible(),
                                "closing-eligible must not be a connection (seed=" + seed + ")");
                    }
                }
            }
        }
    }

    @Test
    void allConnectionsAreBidirectional() {
        List<Row> rows = buildRows(BaseVariant.STANDARD, new Random(42));
        Map<String, Cell> cellById = new HashMap<>();
        for (Row row : rows) {
            for (Cell cell : row.cells()) cellById.put(cell.id(), cell);
        }

        for (Row row : rows) {
            for (Cell cellA : row.cells()) {
                for (CellTag tag : cellA.tags()) {
                    if (tag instanceof CellTag.AutoCross autoCross) {
                        Cell cellB = cellById.get(autoCross.target());
                        assertNotNull(cellB, "target cell must exist");
                        boolean hasReverse = cellB.tags().stream()
                                .anyMatch(t -> t instanceof CellTag.AutoCross ac
                                        && ac.target().equals(cellA.id()));
                        assertTrue(hasReverse, "AutoCross must be bidirectional: " + cellA.id() + " <-> " + cellB.id());
                    }
                }
            }
        }
    }

    @Test
    void intraPairDistanceAtLeast3ForManySeeds() {
        UUID player = UUID.randomUUID();
        for (int seed = 0; seed < 50; seed++) {
            GameSettings settings = GameSettings.builder().connectedCells(true).build();
            ConfigurableGameStyleFactory factory = new ConfigurableGameStyleFactory(settings, new Random(seed));
            List<Row> rows = factory.buildRows(List.of(player)).get(player);

            for (int pair = 0; pair < rows.size() - 1; pair++) {
                List<Integer> positions = connectionsBetween(rows.get(pair), rows.get(pair + 1));
                assertEquals(2, positions.size(),
                        "exactly 2 connections per pair (seed=" + seed + ", pair=" + pair + ")");
                int diff = Math.abs(positions.get(0) - positions.get(1));
                assertTrue(diff >= 3,
                        "intra-pair distance must be >= 3 (seed=" + seed + ", pair=" + pair + ", diff=" + diff + ")");
            }
        }
    }

    @Test
    void noCellHasMoreThanOneAutoCrossTag() {
        for (int seed = 0; seed < 200; seed++) {
            List<Row> rows = buildRows(BaseVariant.STANDARD, new Random(seed));
            for (Row row : rows) {
                for (Cell cell : row.cells()) {
                    long autoCrossCount = cell.tags().stream()
                            .filter(t -> t instanceof CellTag.AutoCross)
                            .count();
                    assertTrue(autoCrossCount <= 1,
                            "cell must not be in more than one connection (seed=" + seed + ", cell=" + cell.id() + ")");
                }
            }
        }
    }

    @Test
    void longoModeRespectsClosingEligible() {
        for (int seed = 0; seed < 20; seed++) {
            List<Row> rows = buildRows(BaseVariant.LONGO, new Random(seed));
            for (Row row : rows) {
                for (Cell cell : row.cells()) {
                    if (cell.tags().stream().anyMatch(t -> t instanceof CellTag.AutoCross)) {
                        assertNotEquals(0, cell.position(),
                                "LONGO: position 0 must not be a connection (seed=" + seed + ")");
                        assertFalse(cell.isClosingEligible(),
                                "LONGO: closing-eligible must not be a connection (seed=" + seed + ")");
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private List<Row> buildRows(BaseVariant base, Random rng) {
        UUID player = UUID.randomUUID();
        GameSettings settings = GameSettings.builder().base(base).connectedCells(true).build();
        return new ConfigurableGameStyleFactory(settings, rng).buildRows(List.of(player)).get(player);
    }

    /** Positions in rowA that have an AutoCross pointing to a cell in rowB. */
    private List<Integer> connectionsBetween(Row rowA, Row rowB) {
        Set<String> bIds = new HashSet<>();
        for (Cell c : rowB.cells()) bIds.add(c.id());

        List<Integer> positions = new ArrayList<>();
        for (Cell cellA : rowA.cells()) {
            for (CellTag tag : cellA.tags()) {
                if (tag instanceof CellTag.AutoCross autoCross && bIds.contains(autoCross.target())) {
                    positions.add(cellA.position());
                }
            }
        }
        return positions;
    }
}
