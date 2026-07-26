package nl.adg.qwixx.game.factory;

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
import nl.adg.qwixx.game.options.BaseVariant;
import nl.adg.qwixx.game.options.GameSettings;
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
                    if (tag instanceof CellTag.AutoCross(String target)) {
                        Cell cellB = cellById.get(target);
                        assertNotNull(cellB, "target cell must exist");
                        boolean hasReverse = cellB.tags().stream()
                                .anyMatch(t -> t instanceof CellTag.AutoCross(String reverse)
                                        && reverse.equals(cellA.id()));
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
                int diff = Math.abs(positions.getFirst() - positions.get(1));
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
    // Connected B — one-way diagonal links
    // -------------------------------------------------------------------------

    @Test
    void diagonalLinksAreOneWay_sourceTaggedTargetIsNot() {
        for (int seed = 0; seed < 50; seed++) {
            List<Row> rows = buildDiagonalRows(BaseVariant.STANDARD, new Random(seed));
            Map<String, Cell> byId = cellsById(rows);
            for (Row row : rows) {
                for (Cell source : row.cells()) {
                    for (CellTag tag : source.tags()) {
                        if (tag instanceof CellTag.AutoCross ac) {
                            Cell target = byId.get(ac.target());
                            assertNotNull(target, "target must exist (seed=" + seed + ")");
                            boolean pointsBack = target.tags().stream().anyMatch(
                                    t -> t instanceof CellTag.AutoCross back && back.target().equals(source.id()));
                            assertFalse(pointsBack,
                                    "Connected B links are one-way: target must not point back (seed=" + seed + ")");
                        }
                    }
                }
            }
        }
    }

    @Test
    void diagonalTargetIsOneRowBelowAndOneColumnOver() {
        for (int seed = 0; seed < 50; seed++) {
            List<Row> rows = buildDiagonalRows(BaseVariant.STANDARD, new Random(seed));
            for (int r = 0; r < rows.size(); r++) {
                for (Cell source : rows.get(r).cells()) {
                    for (CellTag tag : source.tags()) {
                        if (tag instanceof CellTag.AutoCross ac) {
                            assertTrue(r + 1 < rows.size(), "source must have a row below (seed=" + seed + ")");
                            Cell target = findById(rows.get(r + 1), ac.target());
                            assertNotNull(target, "target must be in the row directly below (seed=" + seed + ")");
                            int diff = Math.abs(target.position() - source.position());
                            assertEquals(1, diff,
                                    "target column must be source ±1 (seed=" + seed + ", diff=" + diff + ")");
                        }
                    }
                }
            }
        }
    }

    @Test
    void diagonalTargetsAvoidForbiddenColumns() {
        for (int seed = 0; seed < 50; seed++) {
            List<Row> rows = buildDiagonalRows(BaseVariant.STANDARD, new Random(seed));
            Map<String, Cell> byId = cellsById(rows);
            for (Row row : rows) {
                for (Cell source : row.cells()) {
                    for (CellTag tag : source.tags()) {
                        if (tag instanceof CellTag.AutoCross ac) {
                            Cell target = byId.get(ac.target());
                            assertNotEquals(0, target.position(),
                                    "diagonal target must not be column 0 (seed=" + seed + ")");
                            assertFalse(target.isClosingEligible(),
                                    "diagonal target must not be closing-eligible (seed=" + seed + ")");
                        }
                    }
                }
            }
        }
    }

    @Test
    void noDirectionalChain_aTargetColumnIsNeverASourceColumnBelow() {
        for (int seed = 0; seed < 100; seed++) {
            List<Row> rows = buildDiagonalRows(BaseVariant.STANDARD, new Random(seed));
            Map<String, Cell> byId = cellsById(rows);
            for (int r = 0; r + 1 < rows.size(); r++) {
                Set<Integer> targetCols = new HashSet<>();
                for (Cell source : rows.get(r).cells()) {
                    for (CellTag tag : source.tags()) {
                        if (tag instanceof CellTag.AutoCross ac) targetCols.add(byId.get(ac.target()).position());
                    }
                }
                for (Cell source : rows.get(r + 1).cells()) {
                    boolean isSource = source.tags().stream().anyMatch(t -> t instanceof CellTag.AutoCross);
                    if (isSource) {
                        assertFalse(targetCols.contains(source.position()),
                                "a fired target must not itself be a source one row down (seed=" + seed + ")");
                    }
                }
            }
        }
    }

    @Test
    void connectedCellsAndConnectedDiagonalCannotCombine() {
        assertThrows(IllegalArgumentException.class,
                () -> GameSettings.builder().connectedCells(true).connectedDiagonal(true).build());
    }

    @Test
    void connectedDiagonalAloneBuilds() {
        assertDoesNotThrow(() -> GameSettings.builder().connectedDiagonal(true).build());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private List<Row> buildRows(BaseVariant base, Random rng) {
        UUID player = UUID.randomUUID();
        GameSettings settings = GameSettings.builder().base(base).connectedCells(true).build();
        return new ConfigurableGameStyleFactory(settings, rng).buildRows(List.of(player)).get(player);
    }

    private List<Row> buildDiagonalRows(BaseVariant base, Random rng) {
        UUID player = UUID.randomUUID();
        GameSettings settings = GameSettings.builder().base(base).connectedDiagonal(true).build();
        return new ConfigurableGameStyleFactory(settings, rng).buildRows(List.of(player)).get(player);
    }

    private Map<String, Cell> cellsById(List<Row> rows) {
        Map<String, Cell> byId = new HashMap<>();
        for (Row row : rows) for (Cell c : row.cells()) byId.put(c.id(), c);
        return byId;
    }

    private Cell findById(Row row, String id) {
        for (Cell c : row.cells()) if (c.id().equals(id)) return c;
        return null;
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
