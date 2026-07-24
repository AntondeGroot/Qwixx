package nl.adg.qwixx.game.factory;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
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
import nl.adg.qwixx.data.LockCell;
import nl.adg.qwixx.data.Row;
import nl.adg.qwixx.game.options.BaseVariant;
import nl.adg.qwixx.game.options.GameSettings;
import nl.adg.qwixx.state.CardMode;
import nl.adg.qwixx.state.LongoVariantData;
import nl.adg.qwixx.state.VariantData;
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
    void sameCardsModeAllPlayersShareSameRowInstances() {
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        Map<UUID, List<Row>> result = factory(CardMode.SAME_CARDS).buildRows(List.of(p1, p2));
        assertSame(result.get(p1), result.get(p2));
    }

    @Test
    void differentCardsModeEachPlayerGetsDifferentRowInstances() {
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        Map<UUID, List<Row>> result = factory(CardMode.DIFFERENT_CARDS).buildRows(List.of(p1, p2));
        assertNotSame(result.get(p1), result.get(p2));
    }

    @Test
    void bonusBUniqueCardsKeepStandardColoursButVaryBonusRows() {
        var factory = new ConfigurableGameStyleFactory(
                GameSettings.builder().bonusB(true).cardMode(CardMode.DIFFERENT_CARDS).build(), new Random(3));
        List<UUID> players = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        Map<UUID, List<Row>> cards = factory.buildRows(players);

        List<Color> canonical = List.of(Color.RED, Color.YELLOW, Color.GREEN, Color.BLUE);
        Set<Integer> groupRow = new HashSet<>();
        for (List<Row> rows : cards.values()) {
            // Row colours (and locks) stay in standard order for every player, so row index ≡ lock
            // colour and index-based closures remain correct.
            for (int i = 0; i < 4; i++) {
                assertEquals(canonical.get(i), rows.get(i).cells().get(0).color(), "row " + i + " colour");
                assertEquals(canonical.get(i), rows.get(i).lock().color(), "row " + i + " lock colour");
            }
            // Instead the bonuses move: the group carrying "6 = PLUS_13" lands on a different row per card.
            groupRow.add(rowOfBox(rows, "6", BonusBKind.PLUS_13));
        }
        assertTrue(groupRow.size() > 1, "the row a bonus group lands on should differ across unique cards");
    }

    private int rowOfBox(List<Row> rows, String value, BonusBKind kind) {
        for (int i = 0; i < rows.size(); i++) {
            boolean match = rows.get(i).cells().stream().anyMatch(c -> c.displayValue().equals(value)
                    && c.tags().stream().anyMatch(t -> t instanceof CellTag.BonusB(BonusBKind k) && k == kind));
            if (match) return i;
        }
        return -1;
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
    void extraRowAlwaysPerPlayerEvenInSameCardsMode() {
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
    void randomOrderSameCardsAllPlayersShareSameLayout() {
        UUID p1 = UUID.randomUUID(), p2 = UUID.randomUUID();
        ConfigurableGameStyleFactory f = new ConfigurableGameStyleFactory(
            GameSettings.builder().randomOrder(true).cardMode(CardMode.SAME_CARDS).build(), new Random(1));
        Map<UUID, List<Row>> result = f.buildRows(List.of(p1, p2));
        assertSame(result.get(p1), result.get(p2));
    }

    @Test
    void randomOrderDifferentCardsPlayersGetIndependentInstances() {
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
    void luckyCross_sameCardsModeAllPlayersShareSameRowObjects() {
        UUID p1 = UUID.randomUUID(), p2 = UUID.randomUUID();
        Map<UUID, List<Row>> result = luckyCrossFactory(BaseVariant.STANDARD, CardMode.SAME_CARDS)
                .buildRows(List.of(p1, p2));
        assertSame(result.get(p1), result.get(p2),
                "Same-cards mode must share the same row list across all players");
    }

    @Test
    void luckyCross_differentCardsModeGivesEachPlayerDistinctRows() {
        UUID p1 = UUID.randomUUID(), p2 = UUID.randomUUID();
        Map<UUID, List<Row>> result = luckyCrossFactory(BaseVariant.STANDARD, CardMode.DIFFERENT_CARDS)
                .buildRows(List.of(p1, p2));
        assertNotSame(result.get(p1), result.get(p2),
                "Different-cards mode must give each player a distinct row list");
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

    // ── X-Change row (buildXChangeRow) ──────────────────────────────────────────

    private ConfigurableGameStyleFactory xChangeFactory(BaseVariant base, CardMode mode) {
        return new ConfigurableGameStyleFactory(
                GameSettings.builder().base(base).cardMode(mode).xChange(true).build());
    }

    private Row xChangeRow(BaseVariant base, CardMode mode) {
        return xChangeFactory(base, mode).buildRows(List.of(UUID.randomUUID())).values().iterator().next()
                .stream()
                .filter(r -> r.cells().stream().anyMatch(c -> c.tags().stream().anyMatch(t -> t instanceof CellTag.XChange)))
                .findFirst().orElseThrow(() -> new AssertionError("no x-change row found"));
    }

    private List<int[]> xChangePairs(Row row) {
        List<int[]> pairs = new ArrayList<>();
        for (Cell cell : row.cells()) {
            cell.tags().stream()
                    .filter(t -> t instanceof CellTag.XChange)
                    .map(t -> (CellTag.XChange) t)
                    .forEach(x -> pairs.add(new int[]{x.a(), x.b()}));
        }
        return pairs;
    }

    @Test
    void xChange_standardRowHasExactNinePairs() {
        List<int[]> pairs = xChangePairs(xChangeRow(BaseVariant.STANDARD, CardMode.SAME_CARDS));
        int[][] expected = {{8, 5}, {9, 7}, {11, 3}, {7, 4}, {10, 3}, {8, 6}, {10, 5}, {11, 9}, {6, 4}};
        assertEquals(expected.length, pairs.size());
        for (int i = 0; i < expected.length; i++) {
            assertArrayEquals(expected[i], pairs.get(i), "x-change pair " + i);
        }
    }

    @Test
    void xChange_longoRowHasExactElevenPairs() {
        List<int[]> pairs = xChangePairs(xChangeRow(BaseVariant.LONGO, CardMode.SAME_CARDS));
        int[][] expected = {{11, 6}, {12, 9}, {14, 4}, {10, 7}, {9, 5}, {13, 4}, {11, 7}, {13, 6}, {12, 8}, {14, 12}, {7, 5}};
        assertEquals(expected.length, pairs.size());
        for (int i = 0; i < expected.length; i++) {
            assertArrayEquals(expected[i], pairs.get(i), "x-change pair " + i);
        }
    }

    @Test
    void xChange_cellsAreBlueEmptyAndNotClosingEligible() {
        Row row = xChangeRow(BaseVariant.STANDARD, CardMode.SAME_CARDS);
        for (Cell cell : row.cells()) {
            assertEquals(Color.BLUE, cell.color());
            assertEquals("", cell.displayValue());
            assertFalse(cell.isClosingEligible());
        }
        assertNull(row.lock(), "x-change rows have no lock");
    }

    @Test
    void xChange_cellPositionsAreSequential() {
        Row row = xChangeRow(BaseVariant.STANDARD, CardMode.SAME_CARDS);
        for (int i = 0; i < row.cells().size(); i++) {
            assertEquals(i, row.cells().get(i).position());
        }
    }

    @Test
    void xChange_appendedInDifferentCardsMode() {
        Map<UUID, List<Row>> result = xChangeFactory(BaseVariant.STANDARD, CardMode.DIFFERENT_CARDS)
                .buildRows(List.of(UUID.randomUUID(), UUID.randomUUID()));
        for (List<Row> rows : result.values()) {
            assertTrue(rows.stream().anyMatch(r -> r.cells().stream()
                            .anyMatch(c -> c.tags().stream().anyMatch(t -> t instanceof CellTag.XChange))),
                    "each player must receive an x-change row in different-cards mode");
        }
    }

    // ── Variant data (buildVariantData) ─────────────────────────────────────────

    @Test
    void standardVariantDataIsNull() {
        ConfigurableGameStyleFactory f = new ConfigurableGameStyleFactory(
                GameSettings.builder().base(BaseVariant.STANDARD).build());
        assertNull(f.buildVariantData(List.of(UUID.randomUUID())));
    }

    @Test
    void longoVariantDataPairsAreSortedDistinctUniqueAndExcludeSevenEleven() {
        ConfigurableGameStyleFactory f = new ConfigurableGameStyleFactory(
                GameSettings.builder().base(BaseVariant.LONGO).build(), new Random(7));
        List<UUID> players = new ArrayList<>();
        for (int i = 0; i < 14; i++) players.add(UUID.randomUUID()); // 14 = every valid pair

        VariantData vd = f.buildVariantData(players);
        assertInstanceOf(LongoVariantData.class, vd);
        LongoVariantData lvd = (LongoVariantData) vd;

        Set<Integer> pool = Set.of(5, 6, 7, 11, 12, 13);
        Set<List<Integer>> seen = new HashSet<>();
        for (UUID player : players) {
            List<Integer> pair = lvd.bonusNumbersPerPlayer().get(player);
            assertEquals(2, pair.size(), "each player gets a pair");
            assertTrue(pool.containsAll(pair), "pair values come from the pool");
            assertTrue(pair.get(0) < pair.get(1), "pair must be sorted ascending");
            assertNotEquals(pair.get(0), pair.get(1), "the two bonus numbers must differ");
            assertNotEquals(List.of(7, 11), pair, "pair (7, 11) must be excluded");
            assertTrue(seen.add(pair), "each pair must be unique across players");
        }
        assertEquals(14, seen.size(), "all 14 distinct valid pairs must be assigned");
    }

    // ── Bonus A bar (buildBonusBar / applyBonusBoxes) ───────────────────────────

    private static final Color[] EXPECTED_BONUS_BAR = {
        Color.RED, Color.YELLOW, Color.GREEN, Color.BLUE, Color.GREEN, Color.RED,
        Color.BLUE, Color.YELLOW, Color.RED, Color.YELLOW, Color.BLUE, Color.GREEN
    };

    private Row bonusBar(List<Row> rows) {
        return rows.stream().filter(Row::isBonusBar).findFirst()
                .orElseThrow(() -> new AssertionError("no bonus bar row found"));
    }

    @Test
    void bonusA_barHasTwelveCellsInFixedColourSequence() {
        ConfigurableGameStyleFactory f = new ConfigurableGameStyleFactory(
                GameSettings.builder().bonusA(true).cardMode(CardMode.SAME_CARDS).build());
        Row bar = bonusBar(rows(f));
        assertEquals(EXPECTED_BONUS_BAR.length, bar.cells().size());
        for (int i = 0; i < EXPECTED_BONUS_BAR.length; i++) {
            assertEquals(EXPECTED_BONUS_BAR[i], bar.cells().get(i).color(), "bonus bar cell " + i);
        }
    }

    @Test
    void bonusA_barCellsAreEmptyAndNotClosingEligible() {
        ConfigurableGameStyleFactory f = new ConfigurableGameStyleFactory(
                GameSettings.builder().bonusA(true).cardMode(CardMode.SAME_CARDS).build());
        for (Cell cell : bonusBar(rows(f)).cells()) {
            assertEquals("", cell.displayValue());
            assertFalse(cell.isClosingEligible());
        }
    }

    @Test
    void bonusA_barIsShuffledInDifferentCardsModeButKeepsColourMultiset() {
        ConfigurableGameStyleFactory f = new ConfigurableGameStyleFactory(
                GameSettings.builder().bonusA(true).cardMode(CardMode.DIFFERENT_CARDS).build(), new Random(2));
        Row bar = f.buildRows(List.of(UUID.randomUUID())).values().iterator().next()
                .stream().filter(Row::isBonusBar).findFirst().orElseThrow();
        List<Color> actual = bar.cells().stream().map(Cell::color).toList();
        assertNotEquals(List.of(EXPECTED_BONUS_BAR), actual,
                "different-cards mode must shuffle the bonus bar colour order");
        List<Color> sortedActual = new ArrayList<>(actual);
        List<Color> sortedExpected = new ArrayList<>(List.of(EXPECTED_BONUS_BAR));
        sortedActual.sort(java.util.Comparator.comparing(Enum::name));
        sortedExpected.sort(java.util.Comparator.comparing(Enum::name));
        assertEquals(sortedExpected, sortedActual, "shuffle must preserve the colour multiset");
    }

    @Test
    void bonusA_boxesTaggedAtFixedValuesInColouredRows() {
        ConfigurableGameStyleFactory f = new ConfigurableGameStyleFactory(
                GameSettings.builder().bonusA(true).cardMode(CardMode.SAME_CARDS).build());
        List<Row> rows = rows(f);
        assertBonusBoxValues(rows.get(0), Set.of("3", "6", "9"));   // RED
        assertBonusBoxValues(rows.get(1), Set.of("5", "8", "11"));  // YELLOW
        assertBonusBoxValues(rows.get(2), Set.of("10", "7", "4"));  // GREEN
        assertBonusBoxValues(rows.get(3), Set.of("11", "7", "4"));  // BLUE
    }

    private void assertBonusBoxValues(Row row, Set<String> expectedValues) {
        Set<String> tagged = new HashSet<>();
        for (Cell cell : row.cells()) {
            if (cell.tags().stream().anyMatch(t -> t instanceof CellTag.BonusBox)) {
                tagged.add(cell.displayValue());
            }
        }
        assertEquals(expectedValues, tagged, "bonus-box tagged values");
    }

    // ── Bonus B strip (buildBonusBStrip) ────────────────────────────────────────

    @Test
    void bonusB_stripHasFiveIndicatorsInFixedKindOrder() {
        ConfigurableGameStyleFactory f = new ConfigurableGameStyleFactory(
                GameSettings.builder().bonusB(true).cardMode(CardMode.SAME_CARDS).build());
        Row strip = rows(f).stream().filter(Row::isBonusBStrip).findFirst()
                .orElseThrow(() -> new AssertionError("no bonus B strip found"));
        BonusBKind[] expected = {
            BonusBKind.FEWEST_TWO, BonusBKind.ONE_EACH, BonusBKind.DOUBLE_FEWEST,
            BonusBKind.PLUS_13, BonusBKind.NO_PENALTY
        };
        assertEquals(expected.length, strip.cells().size());
        for (int i = 0; i < expected.length; i++) {
            Cell cell = strip.cells().get(i);
            BonusBKind kind = cell.tags().stream()
                    .filter(t -> t instanceof CellTag.BonusB)
                    .map(t -> ((CellTag.BonusB) t).kind())
                    .findFirst().orElseThrow();
            assertEquals(expected[i], kind, "strip indicator " + i);
            assertEquals("", cell.displayValue());
            assertFalse(cell.isClosingEligible());
        }
    }

    // ── Big Points bonus rows (buildBonusRow) ───────────────────────────────────

    @Test
    void bigPointsDescendingBonusRowDescendsAndIsNotClosing() {
        ConfigurableGameStyleFactory f = new ConfigurableGameStyleFactory(
                GameSettings.builder().base(BaseVariant.STANDARD).bigPoints(true).build());
        List<Row> rows = rows(f);
        // rows: [RED(0), BONUS-RY(1), YELLOW(2), GREEN(3), BONUS-GB(4), BLUE(5)]
        Row bonusGB = rows.get(4);
        assertEquals(List.of("12", "11", "10", "9", "8", "7", "6", "5", "4", "3", "2"),
                bonusGB.cells().stream().map(Cell::displayValue).toList(),
                "descending bonus row must count down from 12 to 2");
        assertTrue(bonusGB.cells().stream().noneMatch(Cell::isClosingEligible),
                "bonus row cells must not be closing-eligible");
        assertNull(bonusGB.lock(), "bonus rows have no lock");
    }

    @Test
    void bigPointsTwoPlayersEachGetSixNonNullRows() {
        UUID p1 = UUID.randomUUID(), p2 = UUID.randomUUID();
        ConfigurableGameStyleFactory f = new ConfigurableGameStyleFactory(
                GameSettings.builder().bigPoints(true).cardMode(CardMode.SAME_CARDS).build());
        Map<UUID, List<Row>> result = f.buildRows(List.of(p1, p2));
        for (List<Row> rows : result.values()) {
            assertEquals(6, rows.size());
            rows.forEach(r -> assertNotNull(r, "no row may be null"));
        }
    }

    @Test
    void bigPointsWithExtraRowTagsColouredCells() {
        ConfigurableGameStyleFactory f = new ConfigurableGameStyleFactory(
                GameSettings.builder().bigPoints(true).extraRow(true).build(), new Random(0));
        long tagged = rows(f).stream().flatMap(r -> r.cells().stream())
                .filter(c -> c.tags().stream().anyMatch(t -> t instanceof CellTag.ExtraBucket))
                .count();
        assertTrue(tagged > 0, "extraRow must add ExtraBucket tags in big-points mode");
    }

    @Test
    void bigPointsWithConnectedCellsTagsColouredCells() {
        ConfigurableGameStyleFactory f = new ConfigurableGameStyleFactory(
                GameSettings.builder().bigPoints(true).connectedCells(true).build(), new Random(0));
        long tagged = rows(f).stream().flatMap(r -> r.cells().stream())
                .filter(c -> c.tags().stream().anyMatch(t -> t instanceof CellTag.AutoCross))
                .count();
        assertTrue(tagged > 0, "connectedCells must add AutoCross tags in big-points mode");
    }

    // ── Double A / Double B twins (applyDoubleVariants / buildTwin) ──────────────

    @Test
    void doubleA_twinsEveryNonClosingCellWithMatchingValueAndColour() {
        ConfigurableGameStyleFactory f = new ConfigurableGameStyleFactory(
                GameSettings.builder().doubleA(true).cardMode(CardMode.SAME_CARDS).build());
        Row red = rows(f).get(0);
        List<Cell> twins = red.cells().stream()
                .filter(c -> c.tags().stream().anyMatch(t -> t instanceof CellTag.DoubleTwin))
                .toList();
        assertEquals(10, twins.size(), "Double A twins all 10 non-closing cells of an 11-cell row");
        for (Cell twin : twins) {
            Cell primary = red.cells().stream()
                    .filter(c -> c.position() == twin.position()
                            && c.tags().stream().noneMatch(t -> t instanceof CellTag.DoubleTwin))
                    .findFirst().orElseThrow();
            assertEquals(primary.displayValue(), twin.displayValue(), "twin shares primary display value");
            assertEquals(primary.color(), twin.color(), "twin shares primary colour");
            assertFalse(twin.isClosingEligible(), "twin is never closing-eligible");
        }
    }

    @Test
    void doubleB_twinsOnlyFixedPositions() {
        ConfigurableGameStyleFactory f = new ConfigurableGameStyleFactory(
                GameSettings.builder().doubleB(true).cardMode(CardMode.SAME_CARDS).build());
        for (Row row : rows(f)) {
            Set<Integer> twinPositions = new HashSet<>();
            for (Cell cell : row.cells()) {
                if (cell.tags().stream().anyMatch(t -> t instanceof CellTag.DoubleTwin)) {
                    twinPositions.add(cell.position());
                }
            }
            assertEquals(Set.of(2, 5, 8), twinPositions, "Double B twins only positions 2, 5, 8");
        }
    }

    @Test
    void doubleB_appliedInDifferentCardsMode() {
        ConfigurableGameStyleFactory f = new ConfigurableGameStyleFactory(
                GameSettings.builder().doubleB(true).cardMode(CardMode.DIFFERENT_CARDS).build(), new Random(1));
        List<Row> rows = f.buildRows(List.of(UUID.randomUUID())).values().iterator().next();
        long twins = rows.stream().flatMap(r -> r.cells().stream())
                .filter(c -> c.tags().stream().anyMatch(t -> t instanceof CellTag.DoubleTwin))
                .count();
        assertEquals(12, twins, "3 twins per coloured row across 4 rows");
    }

    // ── Longo lock (buildLock) ──────────────────────────────────────────────────

    @Test
    void longoLockHasTwoClosingCellsBothEligible() {
        for (Row row : rows(factory(BaseVariant.LONGO, CardMode.SAME_CARDS))) {
            LockCell lock = row.lock();
            assertEquals(2, lock.closingCells().size(), "Longo rows have two closing cells");
            Cell second = row.cells().get(row.cells().size() - 2);
            Cell last = row.cells().get(row.cells().size() - 1);
            assertEquals(List.of(second.id(), last.id()), lock.closingCells());
            assertTrue(second.isClosingEligible(), "second-to-last cell must be closing-eligible");
            assertTrue(last.isClosingEligible(), "last cell must be closing-eligible");
        }
    }

    @Test
    void standardLockHasSingleClosingCell() {
        for (Row row : rows(factory(BaseVariant.STANDARD, CardMode.SAME_CARDS))) {
            assertEquals(1, row.lock().closingCells().size(), "Standard rows have one closing cell");
        }
    }

    // ── Lucky Cross structural detail (positions / colours / values / locks) ─────

    private Set<Integer> crossPositions(Row row) {
        Set<Integer> positions = new HashSet<>();
        for (Cell cell : row.cells()) {
            if (cell.tags().stream().anyMatch(t -> t instanceof CellTag.LuckyCross)) {
                positions.add(cell.position());
            }
        }
        return positions;
    }

    @Test
    void luckyCross_cellPositionsAreSequential() {
        for (Row row : luckyCrossRows(BaseVariant.LONGO)) {
            for (int i = 0; i < row.cells().size(); i++) {
                assertEquals(i, row.cells().get(i).position(), "positions must be 0..n sequential");
            }
        }
    }

    @Test
    void luckyCross_everyCellIncludingCrossFieldsHasRowColour() {
        List<Row> rows = luckyCrossRows(BaseVariant.STANDARD);
        Color[] expected = {Color.RED, Color.YELLOW, Color.GREEN, Color.BLUE};
        for (int r = 0; r < 4; r++) {
            for (Cell cell : rows.get(r).cells()) {
                assertEquals(expected[r], cell.color(), "row " + r + " cell colour");
            }
        }
    }

    @Test
    void luckyCross_descendingRowNormalValuesDescend() {
        Row green = luckyCrossRows(BaseVariant.STANDARD).get(2); // GREEN = descending
        List<String> normal = green.cells().stream()
                .filter(c -> c.tags().stream().noneMatch(t -> t instanceof CellTag.LuckyCross))
                .map(Cell::displayValue).toList();
        assertEquals(List.of("12", "11", "10", "9", "8", "7", "6", "5", "4", "3", "2"), normal);
    }

    @Test
    void luckyCross_everyRowHasALock() {
        for (Row row : luckyCrossRows(BaseVariant.STANDARD)) {
            assertNotNull(row.lock(), "each lucky-cross coloured row must have a lock");
        }
    }

    @Test
    void luckyCross_longoCrossPositionsPerRowUseCyclicShift() {
        List<Row> rows = luckyCrossRows(BaseVariant.LONGO);
        assertEquals(Set.of(2, 6, 11, 16), crossPositions(rows.get(0)), "RED shift 0");
        assertEquals(Set.of(3, 8, 13, 16), crossPositions(rows.get(1)), "YELLOW shift 1");
        assertEquals(Set.of(4, 9, 12, 15), crossPositions(rows.get(2)), "GREEN shift 2");
        assertEquals(Set.of(4, 7, 10, 14), crossPositions(rows.get(3)), "BLUE shift 3");
    }

    @Test
    void luckyCross_appendedInDifferentCardsMode() {
        List<Row> rows = luckyCrossFactory(BaseVariant.STANDARD, CardMode.DIFFERENT_CARDS)
                .buildRows(List.of(UUID.randomUUID())).values().iterator().next();
        for (Row row : rows) {
            assertEquals(3, countLuckyCrossCells(row),
                    "different-cards mode must still build 3 lucky-cross fields per row");
        }
    }

    // ── Lucky Number cell colour and display value (buildLuckyRow) ───────────────

    @Test
    void luckyNumber_cellsAreBlueWithPointDisplayValues() {
        Row lucky = luckyRow(BaseVariant.STANDARD);
        assertTrue(lucky.cells().stream().allMatch(c -> c.color() == Color.BLUE),
                "lucky-number cells are blue");
        assertEquals(List.of("5", "6", "7", "8"),
                lucky.cells().stream().map(Cell::displayValue).toList(),
                "lucky-number cells display their point values");
    }

    @Test
    void luckyNumber_appendedInDifferentCardsMode() {
        ConfigurableGameStyleFactory f = new ConfigurableGameStyleFactory(
                GameSettings.builder().luckyNumber(true).cardMode(CardMode.DIFFERENT_CARDS).build());
        Map<UUID, List<Row>> result = f.buildRows(List.of(UUID.randomUUID(), UUID.randomUUID()));
        for (List<Row> rows : result.values()) {
            assertTrue(rows.stream().anyMatch(Row::isLuckyRow),
                    "each player must receive a lucky row in different-cards mode");
        }
    }

    // ── Random order in different-cards mode (buildRows) ──────────────────────────

    @Test
    void randomOrderDifferentCardsShufflesDisplayValues() {
        ConfigurableGameStyleFactory f = new ConfigurableGameStyleFactory(
                GameSettings.builder().randomOrder(true).cardMode(CardMode.DIFFERENT_CARDS).build(), new Random(1));
        List<Row> rows = f.buildRows(List.of(UUID.randomUUID())).values().iterator().next();
        assertNotEquals(List.of("2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12"),
                rows.get(0).cells().stream().map(Cell::displayValue).toList(),
                "randomOrder must reorder display values in different-cards mode");
        assertEquals(Set.of("2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12"),
                new HashSet<>(rows.get(0).cells().stream().map(Cell::displayValue).toList()),
                "shuffle must preserve the set of values");
    }

    // ── Connected cells structure (applyConnectedCells / pickPairPositions) ──────

    @Test
    void connectedCells_differentCardsModeTagsCells() {
        ConfigurableGameStyleFactory f = new ConfigurableGameStyleFactory(
                GameSettings.builder().connectedCells(true).cardMode(CardMode.DIFFERENT_CARDS).build(), new Random(0));
        long tagged = f.buildRows(List.of(UUID.randomUUID())).values().iterator().next().stream()
                .flatMap(r -> r.cells().stream())
                .filter(c -> c.tags().stream().anyMatch(t -> t instanceof CellTag.AutoCross))
                .count();
        assertTrue(tagged > 0, "connectedCells must add AutoCross tags in different-cards mode");
    }

    @Test
    void connectedCells_pairsAreValidAndNonChained() {
        for (long seed = 0; seed < 15; seed++) {
            ConfigurableGameStyleFactory f = new ConfigurableGameStyleFactory(
                    GameSettings.builder().connectedCells(true).build(), new Random(seed));
            List<Row> rows = f.buildRows(List.of(UUID.randomUUID())).values().iterator().next();
            List<Set<Integer>> pairs = new ArrayList<>();
            for (int i = 0; i < rows.size() - 1; i++) {
                Set<Integer> connected = connectedPositions(rows.get(i), rows.get(i + 1));
                assertEquals(2, connected.size(), "each adjacency connects exactly two positions (seed " + seed + ")");
                List<Integer> pos = new ArrayList<>(connected);
                assertTrue(Math.abs(pos.get(0) - pos.get(1)) >= 3,
                        "intra-pair distance must be >= 3 (seed " + seed + ")");
                assertFalse(connected.contains(0), "position 0 must never be connected (seed " + seed + ")");
                pairs.add(connected);
            }
            for (int i = 0; i < pairs.size() - 1; i++) {
                Set<Integer> shared = new HashSet<>(pairs.get(i));
                shared.retainAll(pairs.get(i + 1));
                assertTrue(shared.isEmpty(),
                        "consecutive adjacencies must not share a position (seed " + seed + ")");
            }
        }
    }

    private Set<Integer> connectedPositions(Row a, Row b) {
        Set<Integer> result = new HashSet<>();
        for (Cell ca : a.cells()) {
            for (CellTag tag : ca.tags()) {
                if (tag instanceof CellTag.AutoCross(String target)) {
                    b.cells().stream().filter(cb -> cb.id().equals(target)).findFirst()
                            .ifPresent(cb -> {
                                assertEquals(ca.position(), cb.position(),
                                        "connected cells must share a column position");
                                result.add(ca.position());
                            });
                }
            }
        }
        return result;
    }

    // --- mixed colours ---

    private List<Row> mixedRows(BaseVariant base, long seed) {
        GameSettings settings = GameSettings.builder().base(base).mixedColors(true).build();
        ConfigurableGameStyleFactory f = new ConfigurableGameStyleFactory(settings, new Random(seed));
        return f.buildRows(List.of(UUID.randomUUID())).values().iterator().next();
    }

    @Test
    void mixedColoursWithExtraRowKeepsFullRangeAndLaysTheWaveOnTop() {
        GameSettings settings = GameSettings.builder().mixedColors(true).extraRow(true).build();
        List<Row> rows = new ConfigurableGameStyleFactory(settings, new Random(7))
                .buildRows(List.of(UUID.randomUUID())).values().iterator().next();

        // The wave only tags cells, so the four colour rows still each span 2..12 once.
        assertEquals(4, rows.size());
        Map<Color, List<Integer>> byColour = new java.util.EnumMap<>(Color.class);
        for (Row row : rows) {
            for (Cell cell : row.cells()) {
                byColour.computeIfAbsent(cell.color(), k -> new ArrayList<>())
                        .add(Integer.parseInt(cell.displayValue()));
            }
        }
        List<Integer> fullRange = new ArrayList<>();
        for (int n = 2; n <= 12; n++) fullRange.add(n);
        for (Color c : List.of(Color.RED, Color.YELLOW, Color.GREEN, Color.BLUE)) {
            List<Integer> nums = new ArrayList<>(byColour.getOrDefault(c, List.of()));
            java.util.Collections.sort(nums);
            assertEquals(fullRange, nums, "colour " + c + " still spans 2..12 once with extraRow");
        }

        // extraRow tagged one cell per column (11) as an extra bucket — the wave across the four rows.
        long waveCells = rows.stream().flatMap(r -> r.cells().stream())
                .filter(c -> c.tags().stream().anyMatch(t -> t instanceof CellTag.ExtraBucket))
                .count();
        assertEquals(11, waveCells, "extraRow lays one extra-bucket cell per column over the mixed rows");
    }

    @Test
    void mixedColoursEachColourSpansTheFullRangeOnceAndLocksFollowCanonicalOrder() {
        List<Color> canonicalLocks = List.of(Color.RED, Color.YELLOW, Color.GREEN, Color.BLUE);
        for (BaseVariant base : BaseVariant.values()) {
            int max = base == BaseVariant.LONGO ? 16 : 12;
            List<Integer> fullRange = new ArrayList<>();
            for (int n = 2; n <= max; n++) fullRange.add(n);
            for (long seed = 0; seed < 300; seed++) {
                List<Row> rows = mixedRows(base, seed);
                assertEquals(4, rows.size(), "four colour rows");

                // Each colour carries every number 2..max exactly once across the four rows.
                Map<Color, List<Integer>> numbersByColour = new java.util.EnumMap<>(Color.class);
                for (Row row : rows) {
                    for (Cell cell : row.cells()) {
                        numbersByColour.computeIfAbsent(cell.color(), k -> new ArrayList<>())
                                .add(Integer.parseInt(cell.displayValue()));
                    }
                }
                for (Color c : List.of(Color.RED, Color.YELLOW, Color.GREEN, Color.BLUE)) {
                    List<Integer> nums = new ArrayList<>(numbersByColour.getOrDefault(c, List.of()));
                    java.util.Collections.sort(nums);
                    assertEquals(fullRange, nums,
                            "colour " + c + " must span 2.." + max + " once (base " + base + ", seed " + seed + ")");
                }

                // Lock colours are pinned to the standard row order for every player, so row index ≡
                // lock colour and index-based row closure stays correct across different cards.
                List<Color> lockColours = rows.stream().map(r -> r.lock().color()).toList();
                assertEquals(canonicalLocks, lockColours,
                        "lock colours follow RED,YELLOW,GREEN,BLUE by row (base " + base + ", seed " + seed + ")");
            }
        }
    }

    @Test
    void mixedColoursHasNoLoneCellsAndSplitsCompositionsTwoAndTwo() {
        for (BaseVariant base : BaseVariant.values()) {
            // number-cell run signatures: even composition vs varied composition.
            // Standard {3,3,3,3}->[2,3,3,3], {2,4,2,4}->[2,2,3,4].
            // Longo    {4,4,4,4}->[3,4,4,4], {3,4,4,2,3}->[2,2,3,4,4] (a colour repeats: five runs).
            List<Integer> evenSig   = base == BaseVariant.LONGO ? List.of(3, 4, 4, 4)    : List.of(2, 3, 3, 3);
            List<Integer> variedSig = base == BaseVariant.LONGO ? List.of(2, 2, 3, 4, 4) : List.of(2, 2, 3, 4);
            for (long seed = 0; seed < 200; seed++) {
                List<Row> rows = mixedRows(base, seed);
                int evenRows = 0;
                int variedRows = 0;
                for (Row row : rows) {
                    List<Integer> runs = new ArrayList<>(colourRunLengths(row));
                    for (int len : runs) {
                        assertTrue(len >= 2, "no lone colour cell (base " + base + ", seed " + seed + ")");
                    }
                    java.util.Collections.sort(runs);
                    if (runs.equals(evenSig)) evenRows++;
                    else if (runs.equals(variedSig)) variedRows++;
                    else fail("unexpected run lengths " + runs + " (base " + base + ", seed " + seed + ")");
                }
                assertEquals(2, evenRows, "two even-composition rows (base " + base + ", seed " + seed + ")");
                assertEquals(2, variedRows, "two varied-composition rows (base " + base + ", seed " + seed + ")");
            }
        }
    }

    private static List<Integer> colourRunLengths(Row row) {
        List<Integer> runs = new ArrayList<>();
        Color prev = null;
        int len = 0;
        for (Cell cell : row.cells()) {
            if (cell.color() == prev) {
                len++;
            } else {
                if (prev != null) runs.add(len);
                prev = cell.color();
                len = 1;
            }
        }
        if (prev != null) runs.add(len);
        return runs;
    }
}
