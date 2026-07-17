package nl.adg.qwixx.game;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import nl.adg.qwixx.data.BonusBKind;
import nl.adg.qwixx.data.Cell;
import nl.adg.qwixx.data.CellTag;
import nl.adg.qwixx.data.Color;
import nl.adg.qwixx.data.Die;
import nl.adg.qwixx.data.Row;
import nl.adg.qwixx.state.CardMode;
import org.junit.jupiter.api.Test;

class ConfigurableGameStyleFactoryTest {

    private ConfigurableGameStyleFactory factory(BaseVariant base, CardMode cardMode) {
        return new ConfigurableGameStyleFactory(
            GameSettings.builder().base(base).cardMode(cardMode).build()
        );
    }

    private ConfigurableGameStyleFactory factory(CardMode cardMode) {
        return factory(BaseVariant.STANDARD, cardMode);
    }

    private List<Row> rows(ConfigurableGameStyleFactory f) {
        return f.buildRows(List.of(UUID.randomUUID())).values().iterator().next();
    }

    // --- row count ---

    @Test
    void hasFourRows() {
        assertEquals(4, rows(factory(CardMode.SAME_CARDS)).size());
    }

    // --- standard cell count ---

    @Test
    void standardEachRowHasElevenCells() {
        for (Row row : rows(factory(BaseVariant.STANDARD, CardMode.SAME_CARDS))) {
            assertEquals(11, row.cells().size());
        }
    }

    @Test
    void longoEachRowHasFifteenCells() {
        for (Row row : rows(factory(BaseVariant.LONGO, CardMode.SAME_CARDS))) {
            assertEquals(15, row.cells().size());
        }
    }

    // --- display values ---

    @Test
    void standardAscendingRowDisplayValues() {
        List<Row> rows = rows(factory(BaseVariant.STANDARD, CardMode.SAME_CARDS));
        assertEquals(List.of("2","3","4","5","6","7","8","9","10","11","12"),
            rows.get(0).cells().stream().map(Cell::displayValue).toList());
        assertEquals(List.of("2","3","4","5","6","7","8","9","10","11","12"),
            rows.get(1).cells().stream().map(Cell::displayValue).toList());
    }

    @Test
    void standardDescendingRowDisplayValues() {
        List<Row> rows = rows(factory(BaseVariant.STANDARD, CardMode.SAME_CARDS));
        assertEquals(List.of("12","11","10","9","8","7","6","5","4","3","2"),
            rows.get(2).cells().stream().map(Cell::displayValue).toList());
        assertEquals(List.of("12","11","10","9","8","7","6","5","4","3","2"),
            rows.get(3).cells().stream().map(Cell::displayValue).toList());
    }

    @Test
    void longoAscendingRowDisplayValues() {
        List<Row> rows = rows(factory(BaseVariant.LONGO, CardMode.SAME_CARDS));
        assertEquals(List.of("2","3","4","5","6","7","8","9","10","11","12","13","14","15","16"),
            rows.get(0).cells().stream().map(Cell::displayValue).toList());
    }

    @Test
    void longoDescendingRowDisplayValues() {
        List<Row> rows = rows(factory(BaseVariant.LONGO, CardMode.SAME_CARDS));
        assertEquals(List.of("16","15","14","13","12","11","10","9","8","7","6","5","4","3","2"),
            rows.get(2).cells().stream().map(Cell::displayValue).toList());
    }

    // --- colors ---

    @Test
    void rowColorsAreCorrect() {
        List<Row> rows = rows(factory(CardMode.SAME_CARDS));
        assertTrue(rows.get(0).cells().stream().allMatch(c -> c.color() == Color.RED));
        assertTrue(rows.get(1).cells().stream().allMatch(c -> c.color() == Color.YELLOW));
        assertTrue(rows.get(2).cells().stream().allMatch(c -> c.color() == Color.GREEN));
        assertTrue(rows.get(3).cells().stream().allMatch(c -> c.color() == Color.BLUE));
    }

    // --- positions ---

    @Test
    void cellPositionsAreSequential() {
        for (Row row : rows(factory(CardMode.SAME_CARDS))) {
            for (int i = 0; i < row.cells().size(); i++) {
                assertEquals(i, row.cells().get(i).position());
            }
        }
    }

    // --- lock ---

    @Test
    void eachRowHasALock() {
        for (Row row : rows(factory(CardMode.SAME_CARDS))) {
            assertNotNull(row.lock());
        }
    }

    @Test
    void lockRequiresSixCrossesAndLastCell() {
        List<Row> rows = rows(factory(CardMode.SAME_CARDS));
        for (Row row : rows) {
            assertEquals(6, row.lock().minCrosses());
            Cell lastCell = row.cells().get(row.cells().size() - 1);
            assertEquals(List.of(lastCell.id()), row.lock().closingCells());
        }
    }

    @Test
    void onlyLastCellIsClosingEligible() {
        for (Row row : rows(factory(CardMode.SAME_CARDS))) {
            int last = row.cells().size() - 1;
            for (int i = 0; i < last; i++) {
                assertFalse(row.cells().get(i).isClosingEligible());
            }
            assertTrue(row.cells().get(last).isClosingEligible());
        }
    }

    @Test
    void lockColorMatchesRowColor() {
        List<Row> rows = rows(factory(CardMode.SAME_CARDS));
        assertEquals(Color.RED,    rows.get(0).lock().color());
        assertEquals(Color.YELLOW, rows.get(1).lock().color());
        assertEquals(Color.GREEN,  rows.get(2).lock().color());
        assertEquals(Color.BLUE,   rows.get(3).lock().color());
    }

    // --- card mode ---

    @Test
    void deterministicModeAllPlayersShareSameRowInstances() {
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        Map<UUID, List<Row>> result = factory(CardMode.SAME_CARDS).buildRows(List.of(p1, p2));
        assertSame(result.get(p1), result.get(p2));
    }

    @Test
    void probabilisticModeEachPlayerGetsDifferentRowInstances() {
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        Map<UUID, List<Row>> result = factory(CardMode.DIFFERENT_CARDS).buildRows(List.of(p1, p2));
        assertNotSame(result.get(p1), result.get(p2));
    }

    @Test
    void bonusBUniqueCardsShuffleColoursButKeepBonusPositions() {
        var factory = new ConfigurableGameStyleFactory(
                GameSettings.builder().bonusB(true).cardMode(CardMode.DIFFERENT_CARDS).build(), new Random(3));
        List<UUID> players = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        Map<UUID, List<Row>> cards = factory.buildRows(players);

        Set<Color> row0Colours = new HashSet<>();
        for (List<Row> rows : cards.values()) {
            // The bonus KIND at each (position, value) is identical for every player — distances fixed.
            assertKind(rows.get(0), "6", BonusBKind.PLUS_13);
            assertKind(rows.get(1), "11", BonusBKind.FEWEST_TWO);
            assertKind(rows.get(2), "9", BonusBKind.FEWEST_TWO);
            assertKind(rows.get(3), "4", BonusBKind.PLUS_13);
            row0Colours.add(rows.get(0).cells().get(0).color());
        }
        // ...but the colour of a given row position varies across players.
        assertTrue(row0Colours.size() > 1, "row-0 colour should differ across shuffled cards");
    }

    private void assertKind(Row row, String value, BonusBKind expected) {
        Cell cell = row.cells().stream().filter(c -> c.displayValue().equals(value)).findFirst().orElseThrow();
        assertTrue(cell.tags().stream().anyMatch(t -> t instanceof CellTag.BonusB(BonusBKind k) && k == expected),
                "cell " + value + " should be " + expected);
    }

    // --- extra row ---

    @Test
    void noExtraRowTagsByDefault() {
        long count = rows(factory(CardMode.SAME_CARDS)).stream()
                .flatMap(row -> row.cells().stream())
                .filter(cell -> cell.tags().stream().anyMatch(t -> t instanceof CellTag.ExtraBucket))
                .count();
        assertEquals(0, count);
    }

    @Test
    void extraRowTagsExactlyOneCellPerColumn() {
        ConfigurableGameStyleFactory f = new ConfigurableGameStyleFactory(
            GameSettings.builder().extraRow(true).build(), new Random(0));
        List<Row> rows = f.buildRows(List.of(UUID.randomUUID())).values().iterator().next();
        int numCols = rows.get(0).cells().size();
        for (int col = 0; col < numCols; col++) {
            final int c = col;
            long tagged = rows.stream()
                    .filter(row -> row.cells().get(c).tags().stream().anyMatch(t -> t instanceof CellTag.ExtraBucket))
                    .count();
            assertEquals(1, tagged, "column " + col + " should have exactly one ExtraBucket cell");
        }
    }

    @Test
    void extraRowAdjacentColumnsAreInAdjacentRows() {
        ConfigurableGameStyleFactory f = new ConfigurableGameStyleFactory(
            GameSettings.builder().extraRow(true).build(), new Random(0));
        List<Row> rows = f.buildRows(List.of(UUID.randomUUID())).values().iterator().next();
        int numCols = rows.get(0).cells().size();
        int[] assigned = new int[numCols];
        for (int col = 0; col < numCols; col++) {
            for (int r = 0; r < rows.size(); r++) {
                if (rows.get(r).cells().get(col).tags().stream().anyMatch(t -> t instanceof CellTag.ExtraBucket))
                    assigned[col] = r;
            }
        }
        for (int col = 1; col < numCols; col++) {
            assertEquals(1, Math.abs(assigned[col] - assigned[col - 1]),
                    "columns " + (col - 1) + " and " + col + " should be in adjacent rows");
        }
    }

    @Test
    void extraRowAlwaysPerPlayerEvenInDeterministicMode() {
        UUID p1 = UUID.randomUUID(), p2 = UUID.randomUUID();
        ConfigurableGameStyleFactory f = new ConfigurableGameStyleFactory(
            GameSettings.builder().extraRow(true).cardMode(CardMode.SAME_CARDS).build(), new Random(0));
        Map<UUID, List<Row>> result = f.buildRows(List.of(p1, p2));
        assertNotSame(result.get(p1), result.get(p2));
    }

    // --- random order ---

    @Test
    void randomOrderPreservesAllValues() {
        ConfigurableGameStyleFactory f = new ConfigurableGameStyleFactory(
            GameSettings.builder().randomOrder(true).build(), new Random(1));
        List<Row> rows = rows(f);
        Set<String> expected = Set.of("2","3","4","5","6","7","8","9","10","11","12");
        for (Row row : rows) {
            assertEquals(expected, new HashSet<>(row.cells().stream().map(Cell::displayValue).toList()));
        }
    }

    @Test
    void randomOrderChangesDisplayValueOrder() {
        ConfigurableGameStyleFactory f = new ConfigurableGameStyleFactory(
            GameSettings.builder().randomOrder(true).build(), new Random(1));
        List<Row> rows = rows(f);
        assertNotEquals(List.of("2","3","4","5","6","7","8","9","10","11","12"),
            rows.get(0).cells().stream().map(Cell::displayValue).toList());
    }

    @Test
    void randomOrderDeterministicAllPlayersShareSameLayout() {
        UUID p1 = UUID.randomUUID(), p2 = UUID.randomUUID();
        ConfigurableGameStyleFactory f = new ConfigurableGameStyleFactory(
            GameSettings.builder().randomOrder(true).cardMode(CardMode.SAME_CARDS).build(), new Random(1));
        Map<UUID, List<Row>> result = f.buildRows(List.of(p1, p2));
        assertSame(result.get(p1), result.get(p2));
    }

    @Test
    void randomOrderProbabilisticPlayersGetIndependentInstances() {
        UUID p1 = UUID.randomUUID(), p2 = UUID.randomUUID();
        ConfigurableGameStyleFactory f = new ConfigurableGameStyleFactory(
            GameSettings.builder().randomOrder(true).cardMode(CardMode.DIFFERENT_CARDS).build(), new Random(1));
        Map<UUID, List<Row>> result = f.buildRows(List.of(p1, p2));
        assertNotSame(result.get(p1), result.get(p2));
    }

    // --- big points ---

    @Test
    void bigPointsStandardBonusRowAlignedWithRedRow() {
        ConfigurableGameStyleFactory f = new ConfigurableGameStyleFactory(
            GameSettings.builder().base(BaseVariant.STANDARD).bigPoints(true).build());
        List<Row> rows = rows(f);
        // rows: [RED(0), BONUS-RY(1), YELLOW(2), GREEN(3), BONUS-GB(4), BLUE(5)]
        List<String> redValues   = rows.get(0).cells().stream().map(Cell::displayValue).toList();
        List<String> bonusValues = rows.get(1).cells().stream().map(Cell::displayValue).toList();
        assertEquals(redValues, bonusValues,
            "Bonus row display values must match RED row for all positions");
    }

    @Test
    void bigPointsLongoBonusRowAlignedWithRedRow() {
        ConfigurableGameStyleFactory f = new ConfigurableGameStyleFactory(
            GameSettings.builder().base(BaseVariant.LONGO).bigPoints(true).build());
        List<Row> rows = rows(f);
        List<String> redValues   = rows.get(0).cells().stream().map(Cell::displayValue).toList();
        List<String> bonusValues = rows.get(1).cells().stream().map(Cell::displayValue).toList();
        assertEquals(redValues, bonusValues,
            "Bonus row display values must match RED row for all positions");
    }

    // --- dice ---

    @Test
    void standardDiceAreSixSided() {
        List<Die> dice = factory(BaseVariant.STANDARD, CardMode.SAME_CARDS).buildDice();
        assertEquals(6, dice.size());
        assertEquals(2, dice.stream().filter(d -> d.color() == Color.WHITE).count());
        assertEquals(1, dice.stream().filter(d -> d.color() == Color.RED).count());
        assertEquals(1, dice.stream().filter(d -> d.color() == Color.YELLOW).count());
        assertEquals(1, dice.stream().filter(d -> d.color() == Color.GREEN).count());
        assertEquals(1, dice.stream().filter(d -> d.color() == Color.BLUE).count());
        assertTrue(dice.stream().allMatch(d -> d.faces() == 6));
    }

    @Test
    void longoDiceAreEightSided() {
        List<Die> dice = factory(BaseVariant.LONGO, CardMode.SAME_CARDS).buildDice();
        assertEquals(6, dice.size());
        assertTrue(dice.stream().allMatch(d -> d.faces() == 8));
    }

    // ── Lucky Number ──────────────────────────────────────────────────────────

    private ConfigurableGameStyleFactory luckyNumberFactory(BaseVariant base) {
        return new ConfigurableGameStyleFactory(
                GameSettings.builder().base(base).luckyNumber(true).build());
    }

    private Row luckyRow(BaseVariant base) {
        return luckyNumberFactory(base)
                .buildRows(List.of(UUID.randomUUID())).values().iterator().next()
                .stream().filter(Row::isLuckyRow).findFirst()
                .orElseThrow(() -> new AssertionError("no lucky row found"));
    }

    @Test
    void luckyNumber_standardRowIsMarkedAsLucky() {
        assertTrue(luckyRow(BaseVariant.STANDARD).isLuckyRow());
    }

    @Test
    void luckyNumber_standardTargetIs13() {
        assertEquals(13, luckyRow(BaseVariant.STANDARD).luckyTarget());
    }

    @Test
    void luckyNumber_longoTargetIs18() {
        assertEquals(18, luckyRow(BaseVariant.LONGO).luckyTarget());
    }

    @Test
    void luckyNumber_rowHasFourCells() {
        assertEquals(4, luckyRow(BaseVariant.STANDARD).cells().size());
    }

    @Test
    void luckyNumber_allCellsHaveLuckyNumberTag() {
        for (Cell cell : luckyRow(BaseVariant.STANDARD).cells()) {
            assertTrue(cell.tags().stream().anyMatch(t -> t instanceof CellTag.LuckyNumber),
                    "Every cell in the lucky row must have a LuckyNumber tag");
        }
    }

    @Test
    void luckyNumber_bonusPointsAre5_6_7_8() {
        List<Integer> pts = luckyRow(BaseVariant.STANDARD).cells().stream()
                .map(c -> c.tags().stream()
                        .filter(t -> t instanceof CellTag.LuckyNumber)
                        .map(t -> ((CellTag.LuckyNumber) t).bonusPoints())
                        .findFirst().orElseThrow())
                .toList();
        assertEquals(List.of(5, 6, 7, 8), pts);
    }

    @Test
    void luckyNumber_cellsAreNotClosingEligible() {
        assertTrue(luckyRow(BaseVariant.STANDARD).cells().stream()
                .noneMatch(Cell::isClosingEligible));
    }

    // ── Lucky Cross ───────────────────────────────────────────────────────────

    private ConfigurableGameStyleFactory luckyCrossFactory(BaseVariant base, CardMode mode) {
        return new ConfigurableGameStyleFactory(
                GameSettings.builder().base(base).cardMode(mode).luckyCross(true).build());
    }

    private List<Row> luckyCrossRows(BaseVariant base) {
        return luckyCrossFactory(base, CardMode.SAME_CARDS)
                .buildRows(List.of(UUID.randomUUID())).values().iterator().next();
    }

    private long countLuckyCrossCells(Row row) {
        return row.cells().stream()
                .filter(c -> c.tags().stream().anyMatch(t -> t instanceof CellTag.LuckyCross))
                .count();
    }

    @Test
    void luckyCross_standard_eachRowHas3CrossFields() {
        for (Row row : luckyCrossRows(BaseVariant.STANDARD)) {
            assertEquals(3, countLuckyCrossCells(row),
                    "Standard: each coloured row must have exactly 3 Lucky Cross fields");
        }
    }

    @Test
    void luckyCross_longo_eachRowHas4CrossFields() {
        for (Row row : luckyCrossRows(BaseVariant.LONGO)) {
            assertEquals(4, countLuckyCrossCells(row),
                    "Longo: each coloured row must have exactly 4 Lucky Cross fields");
        }
    }

    @Test
    void luckyCross_standard_totalCellsPerRowIs14() {
        for (Row row : luckyCrossRows(BaseVariant.STANDARD)) {
            assertEquals(14, row.cells().size(),
                    "Standard: 11 normal + 3 Lucky Cross = 14 cells per row");
        }
    }

    @Test
    void luckyCross_longo_totalCellsPerRowIs19() {
        for (Row row : luckyCrossRows(BaseVariant.LONGO)) {
            assertEquals(19, row.cells().size(),
                    "Longo: 15 normal + 4 Lucky Cross = 19 cells per row");
        }
    }

    @Test
    void luckyCross_crossFieldsAreNotClosingEligible() {
        for (Row row : luckyCrossRows(BaseVariant.STANDARD)) {
            for (Cell cell : row.cells()) {
                if (cell.tags().stream().anyMatch(t -> t instanceof CellTag.LuckyCross)) {
                    assertFalse(cell.isClosingEligible(),
                            "Lucky Cross fields must not be closing-eligible");
                }
            }
        }
    }

    @Test
    void luckyCross_normalCellsHaveCorrectDisplayValuesAscending() {
        Row red = luckyCrossRows(BaseVariant.STANDARD).get(0); // RED = ascending
        List<String> normalValues = red.cells().stream()
                .filter(c -> c.tags().stream().noneMatch(t -> t instanceof CellTag.LuckyCross))
                .map(Cell::displayValue)
                .toList();
        assertEquals(11, normalValues.size());
        for (int i = 0; i < normalValues.size(); i++) {
            assertEquals(String.valueOf(i + 2), normalValues.get(i),
                    "Normal cell " + i + " must have display value " + (i + 2));
        }
    }

    @Test
    void luckyCross_crossFieldPositionsDifferBetweenRows() {
        List<Row> rows = luckyCrossRows(BaseVariant.STANDARD);
        List<List<Integer>> crossPositionsPerRow = rows.stream()
                .map(row -> row.cells().stream()
                        .filter(c -> c.tags().stream().anyMatch(t -> t instanceof CellTag.LuckyCross))
                        .map(Cell::position)
                        .toList())
                .toList();
        // All four rows must have different cross-field position sets (shifted pattern).
        Set<List<Integer>> unique = new HashSet<>(crossPositionsPerRow);
        assertEquals(4, unique.size(),
                "Each row must have a distinct Lucky Cross field position pattern (cyclic shift)");
    }

    @Test
    void luckyCross_deterministicModeAllPlayersShareSameRowObjects() {
        UUID p1 = UUID.randomUUID(), p2 = UUID.randomUUID();
        Map<UUID, List<Row>> result = luckyCrossFactory(BaseVariant.STANDARD, CardMode.SAME_CARDS)
                .buildRows(List.of(p1, p2));
        assertSame(result.get(p1), result.get(p2),
                "Deterministic mode must share the same row list across all players");
    }

    @Test
    void luckyCross_probabilisticModeGivesEachPlayerDistinctRows() {
        UUID p1 = UUID.randomUUID(), p2 = UUID.randomUUID();
        Map<UUID, List<Row>> result = luckyCrossFactory(BaseVariant.STANDARD, CardMode.DIFFERENT_CARDS)
                .buildRows(List.of(p1, p2));
        assertNotSame(result.get(p1), result.get(p2),
                "Probabilistic mode must give each player a distinct row list");
    }

    @Test
    void luckyCross_randomOrderDoesNotMoveLuckyCrossFields() {
        ConfigurableGameStyleFactory f = new ConfigurableGameStyleFactory(
                GameSettings.builder()
                        .base(BaseVariant.STANDARD)
                        .luckyCross(true)
                        .randomOrder(true)
                        .build(),
                new Random(42));
        List<Row> rows = f.buildRows(List.of(UUID.randomUUID())).values().iterator().next();
        for (Row row : rows) {
            // Lucky Cross fields must still have empty display values after shuffle
            for (Cell cell : row.cells()) {
                if (cell.tags().stream().anyMatch(t -> t instanceof CellTag.LuckyCross)) {
                    assertEquals("", cell.displayValue(),
                            "randomOrder must not overwrite Lucky Cross field display values");
                }
            }
        }
    }
}
