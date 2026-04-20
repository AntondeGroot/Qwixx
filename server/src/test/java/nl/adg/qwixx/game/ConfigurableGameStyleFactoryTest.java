package nl.adg.qwixx.game;

import nl.adg.qwixx.data.Cell;
import nl.adg.qwixx.data.Color;
import nl.adg.qwixx.data.Die;
import nl.adg.qwixx.data.Row;
import nl.adg.qwixx.state.CardMode;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

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
        assertEquals(4, rows(factory(CardMode.DETERMINISTIC)).size());
    }

    // --- standard cell count ---

    @Test
    void standardEachRowHasElevenCells() {
        for (Row row : rows(factory(BaseVariant.STANDARD, CardMode.DETERMINISTIC))) {
            assertEquals(11, row.cells().size());
        }
    }

    @Test
    void longoEachRowHasFifteenCells() {
        for (Row row : rows(factory(BaseVariant.LONGO, CardMode.DETERMINISTIC))) {
            assertEquals(15, row.cells().size());
        }
    }

    // --- display values ---

    @Test
    void standardAscendingRowDisplayValues() {
        List<Row> rows = rows(factory(BaseVariant.STANDARD, CardMode.DETERMINISTIC));
        assertEquals(List.of("2","3","4","5","6","7","8","9","10","11","12"),
            rows.get(0).cells().stream().map(Cell::displayValue).toList());
        assertEquals(List.of("2","3","4","5","6","7","8","9","10","11","12"),
            rows.get(1).cells().stream().map(Cell::displayValue).toList());
    }

    @Test
    void standardDescendingRowDisplayValues() {
        List<Row> rows = rows(factory(BaseVariant.STANDARD, CardMode.DETERMINISTIC));
        assertEquals(List.of("12","11","10","9","8","7","6","5","4","3","2"),
            rows.get(2).cells().stream().map(Cell::displayValue).toList());
        assertEquals(List.of("12","11","10","9","8","7","6","5","4","3","2"),
            rows.get(3).cells().stream().map(Cell::displayValue).toList());
    }

    @Test
    void longoAscendingRowDisplayValues() {
        List<Row> rows = rows(factory(BaseVariant.LONGO, CardMode.DETERMINISTIC));
        assertEquals(List.of("2","3","4","5","6","7","8","9","10","11","12","13","14","15","16"),
            rows.get(0).cells().stream().map(Cell::displayValue).toList());
    }

    @Test
    void longoDescendingRowDisplayValues() {
        List<Row> rows = rows(factory(BaseVariant.LONGO, CardMode.DETERMINISTIC));
        assertEquals(List.of("16","15","14","13","12","11","10","9","8","7","6","5","4","3","2"),
            rows.get(2).cells().stream().map(Cell::displayValue).toList());
    }

    // --- colors ---

    @Test
    void rowColorsAreCorrect() {
        List<Row> rows = rows(factory(CardMode.DETERMINISTIC));
        assertTrue(rows.get(0).cells().stream().allMatch(c -> c.color() == Color.RED));
        assertTrue(rows.get(1).cells().stream().allMatch(c -> c.color() == Color.YELLOW));
        assertTrue(rows.get(2).cells().stream().allMatch(c -> c.color() == Color.GREEN));
        assertTrue(rows.get(3).cells().stream().allMatch(c -> c.color() == Color.BLUE));
    }

    // --- positions ---

    @Test
    void cellPositionsAreSequential() {
        for (Row row : rows(factory(CardMode.DETERMINISTIC))) {
            for (int i = 0; i < row.cells().size(); i++) {
                assertEquals(i, row.cells().get(i).position());
            }
        }
    }

    // --- lock ---

    @Test
    void eachRowHasALock() {
        for (Row row : rows(factory(CardMode.DETERMINISTIC))) {
            assertNotNull(row.lock());
        }
    }

    @Test
    void lockRequiresFiveCrossesAndLastCell() {
        List<Row> rows = rows(factory(CardMode.DETERMINISTIC));
        for (Row row : rows) {
            assertEquals(5, row.lock().minCrosses());
            Cell lastCell = row.cells().get(row.cells().size() - 1);
            assertEquals(List.of(lastCell.id()), row.lock().requiredCells());
        }
    }

    @Test
    void onlyLastCellIsClosingEligible() {
        for (Row row : rows(factory(CardMode.DETERMINISTIC))) {
            int last = row.cells().size() - 1;
            for (int i = 0; i < last; i++) {
                assertFalse(row.cells().get(i).isClosingEligible());
            }
            assertTrue(row.cells().get(last).isClosingEligible());
        }
    }

    @Test
    void lockColorMatchesRowColor() {
        List<Row> rows = rows(factory(CardMode.DETERMINISTIC));
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
        Map<UUID, List<Row>> result = factory(CardMode.DETERMINISTIC).buildRows(List.of(p1, p2));
        assertSame(result.get(p1), result.get(p2));
    }

    @Test
    void probabilisticModeEachPlayerGetsDifferentRowInstances() {
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        Map<UUID, List<Row>> result = factory(CardMode.PROBABILISTIC).buildRows(List.of(p1, p2));
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
            GameSettings.builder().randomOrder(true).cardMode(CardMode.DETERMINISTIC).build(), new Random(1));
        Map<UUID, List<Row>> result = f.buildRows(List.of(p1, p2));
        assertSame(result.get(p1), result.get(p2));
    }

    @Test
    void randomOrderProbabilisticPlayersGetIndependentInstances() {
        UUID p1 = UUID.randomUUID(), p2 = UUID.randomUUID();
        ConfigurableGameStyleFactory f = new ConfigurableGameStyleFactory(
            GameSettings.builder().randomOrder(true).cardMode(CardMode.PROBABILISTIC).build(), new Random(1));
        Map<UUID, List<Row>> result = f.buildRows(List.of(p1, p2));
        assertNotSame(result.get(p1), result.get(p2));
    }

    // --- dice ---

    @Test
    void standardDiceAreSixSided() {
        List<Die> dice = factory(BaseVariant.STANDARD, CardMode.DETERMINISTIC).buildDice();
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
        List<Die> dice = factory(BaseVariant.LONGO, CardMode.DETERMINISTIC).buildDice();
        assertEquals(6, dice.size());
        assertTrue(dice.stream().allMatch(d -> d.faces() == 8));
    }
}