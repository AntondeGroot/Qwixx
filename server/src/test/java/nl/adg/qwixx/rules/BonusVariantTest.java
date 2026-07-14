package nl.adg.qwixx.rules;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import nl.adg.qwixx.action.CrossCellAction;
import nl.adg.qwixx.action.DiceCombination;
import nl.adg.qwixx.action.GameAction;
import nl.adg.qwixx.action.RollAction;
import nl.adg.qwixx.data.Cell;
import nl.adg.qwixx.data.CellTag;
import nl.adg.qwixx.data.Color;
import nl.adg.qwixx.data.Die;
import nl.adg.qwixx.data.LockCell;
import nl.adg.qwixx.data.Row;
import nl.adg.qwixx.game.ConfigurableGameStyleFactory;
import nl.adg.qwixx.game.GameSettings;
import nl.adg.qwixx.state.BoardState;
import nl.adg.qwixx.state.CardMode;
import nl.adg.qwixx.state.GameState;
import nl.adg.qwixx.state.RowState;
import nl.adg.qwixx.state.SheetLayout;
import nl.adg.qwixx.state.SheetProgress;
import nl.adg.qwixx.state.TurnPhase;
import nl.adg.qwixx.state.TurnState;
import org.junit.jupiter.api.Test;

/**
 * Bonus A: crossing a bonus box consumes the next bonus-bar cell and forces a cross in that
 * colour's row (chaining if the forced box is itself a bonus box); locking a row forfeits its
 * bonus-bar cells. Fixed dice: white1=3, white2=4 (WW=7), red=2, yellow=3, green=4, blue=5.
 */
class BonusVariantTest {

    private static final int RED = 0, YELLOW = 1, GREEN = 2, BLUE = 3, BAR = 4;
    private final UUID p1 = UUID.randomUUID();
    private final StandardTurnRules rules = new StandardTurnRules(fixedRandom());

    @Test
    void crossingABonusBoxConsumesTheBarAndForcesACross() {
        List<Row> rows = board();
        Cell redBonus = rows.get(RED).cells().get(5); // dv "7", tagged as a bonus box below
        addBonusBox(redBonus);
        // Bar cell 0 is GREEN → forced cross lands in the green row's first box.
        GameState state = stateWithBar(rows, List.of(Color.GREEN, Color.YELLOW, Color.RED));
        rules.apply(state, new RollAction(p1));

        rules.apply(state, new CrossCellAction(p1, RED, redBonus.id(), DiceCombination.WHITE_WHITE));

        assertTrue(crossed(state, RED).contains(redBonus.id()), "the bonus box itself is crossed");
        assertTrue(crossed(state, BAR).contains(rows.get(BAR).cells().get(0).id()), "bar cell 0 consumed");
        assertEquals(1, crossed(state, GREEN).size(), "one forced cross in the green row");
        assertTrue(crossed(state, GREEN).contains(rows.get(GREEN).cells().get(0).id()),
                "forced cross is the green row's next (left-most) box");
    }

    @Test
    void bonusBoxesChain() {
        List<Row> rows = board();
        Cell redBonus = rows.get(RED).cells().get(5);
        addBonusBox(redBonus);
        Cell greenFirst = rows.get(GREEN).cells().get(0); // the forced green box — also a bonus box
        addBonusBox(greenFirst);
        // Bar: GREEN then YELLOW → red-bonus → green forced (bonus) → yellow forced.
        GameState state = stateWithBar(rows, List.of(Color.GREEN, Color.YELLOW, Color.RED));
        rules.apply(state, new RollAction(p1));

        rules.apply(state, new CrossCellAction(p1, RED, redBonus.id(), DiceCombination.WHITE_WHITE));

        assertTrue(crossed(state, GREEN).contains(greenFirst.id()), "green forced box crossed");
        assertEquals(2, crossed(state, BAR).size(), "two bar cells consumed by the chain");
        assertEquals(1, crossed(state, YELLOW).size(), "chain forced one cross in the yellow row");
        assertTrue(crossed(state, YELLOW).contains(rows.get(YELLOW).cells().get(0).id()),
                "yellow forced cross is its next box");
    }

    @Test
    void lockingARowForfeitsItsBonusBarCells() {
        List<Row> rows = board();
        // Bar: green, green, red, green — three green cells + one red.
        GameState state = stateWithBar(rows, List.of(Color.GREEN, Color.GREEN, Color.RED, Color.GREEN));

        RowClosureEvaluator.closeRowGlobally(state, p1, GREEN);

        Set<String> bar = crossed(state, BAR);
        for (Cell c : rows.get(BAR).cells()) {
            if (c.color() == Color.GREEN) {
                assertTrue(bar.contains(c.id()), "green bar cell must be forfeited when green locks");
            } else {
                assertFalse(bar.contains(c.id()), "non-green bar cells are untouched");
            }
        }
    }

    @Test
    void bonusBarCellsAreNeverOffered() {
        List<Row> rows = board();
        GameState state = stateWithBar(rows, List.of(Color.RED, Color.YELLOW, Color.GREEN));
        rules.apply(state, new RollAction(p1));

        List<String> barIds = rows.get(BAR).cells().stream().map(Cell::id).toList();
        for (GameAction a : rules.getValidActions(state, p1)) {
            if (a instanceof CrossCellAction cross) {
                assertFalse(barIds.contains(cross.cellId()), "bonus-bar cells must not be clickable");
            }
        }
    }

    @Test
    void factoryGeneratesThreeBonusBoxesPerRowAndATwelveCellBar() {
        UUID player = UUID.randomUUID();
        List<Row> rows = new ConfigurableGameStyleFactory(GameSettings.builder().bonusA(true).build())
                .buildRows(List.of(player)).get(player);

        List<Row> coloured = rows.stream().filter(r -> r.lock() != null && !r.isBonusBar()).toList();
        assertEquals(4, coloured.size(), "four coloured rows");
        for (Row row : coloured) {
            long boxes = row.cells().stream()
                    .filter(c -> c.tags().stream().anyMatch(t -> t instanceof CellTag.BonusBox)).count();
            assertEquals(3, boxes, "each coloured row has 3 bonus boxes");
        }
        Row bar = rows.stream().filter(Row::isBonusBar).findFirst().orElseThrow();
        assertEquals(12, bar.cells().size(), "the bonus bar has 12 cells");
        for (Color c : List.of(Color.RED, Color.YELLOW, Color.GREEN, Color.BLUE)) {
            assertEquals(3, bar.cells().stream().filter(x -> x.color() == c).count(),
                    "3 bonus-bar cells of each colour");
        }
    }

    @Test
    void bonusAScoreHasOnlyTheFourColourColumns() {
        // With ONLY Bonus A enabled the final score must show just the 4 colour columns —
        // no EXTRA column (extraPoints) and no BONUS column (bonusPoints), which is exactly
        // what the score screen keys off (score.component.ts: EXTRA/BONUS shown only when > 0).
        UUID player = UUID.randomUUID();
        List<Row> rows = new ConfigurableGameStyleFactory(GameSettings.builder().bonusA(true).build())
                .buildRows(List.of(player)).get(player);
        Map<Integer, RowState> rowStates = new HashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            Set<String> cross = new HashSet<>();
            if (row.isBonusBar()) {
                for (Cell c : row.cells()) cross.add(c.id());               // whole bar crossed
            } else if (row.lock() != null) {
                for (int j = 0; j < 3; j++) cross.add(row.cells().get(j).id()); // 3 crosses per colour
            }
            if (!cross.isEmpty()) rowStates.put(i, new RowState(cross, false));
        }
        var score = new StandardScoringEngine().calculate(new SheetLayout(rows), new SheetProgress(rowStates, 0));

        assertEquals(0, score.extraCrosses(), "no extra bucket");
        assertEquals(0, score.extraPoints(), "no EXTRA column on the final score");
        assertEquals(0, score.bonusPoints(), "no BONUS column on the final score");
        for (Color c : List.of(Color.RED, Color.YELLOW, Color.GREEN, Color.BLUE)) {
            assertEquals(6, score.pointsPerColor().get(c), c + " scores triangular(3) = 6");
        }
        assertEquals(24, score.total(), "only the four colour columns contribute (4 × 6)");
    }

    @Test
    void bonusBarCellsScoreNoPoints() {
        List<Row> rows = board();
        rows.add(bonusBar(List.of(Color.RED, Color.RED, Color.GREEN, Color.BLUE))); // index BAR
        // Every bonus-bar cell crossed (consumed or forfeited) — none must count.
        Set<String> barCrossed = new HashSet<>();
        for (Cell c : rows.get(BAR).cells()) barCrossed.add(c.id());
        Map<Integer, RowState> rowStates = new HashMap<>();
        rowStates.put(BAR, new RowState(barCrossed, false));
        SheetLayout layout = new SheetLayout(rows);
        SheetProgress progress = new SheetProgress(rowStates, 0);

        var score = new StandardScoringEngine().calculate(layout, progress);
        assertEquals(0, score.crossesPerColor().getOrDefault(Color.RED, 0), "bonus-bar RED cells add no crosses");
        assertEquals(0, score.crossesPerColor().getOrDefault(Color.GREEN, 0), "bonus-bar GREEN cells add no crosses");
        assertEquals(0, score.total(), "a sheet with only bonus-bar crosses scores nothing");
    }

    @Test
    void forfeitAppliesToEveryPlayer() {
        UUID p2 = UUID.randomUUID();
        List<Row> rows = board();
        rows.add(bonusBar(List.of(Color.GREEN, Color.RED, Color.GREEN)));
        Map<UUID, SheetLayout> layouts = new HashMap<>();
        Map<UUID, SheetProgress> progresses = new HashMap<>();
        for (UUID pid : List.of(p1, p2)) {
            layouts.put(pid, new SheetLayout(rows));
            progresses.put(pid, new SheetProgress(new HashMap<>(), 0));
        }
        BoardState board = new BoardState(progresses, new ArrayList<>(), new HashMap<>());
        GameState state = new GameState(CardMode.DETERMINISTIC, List.of(p1, p2), null, layouts, board, null);

        RowClosureEvaluator.closeRowGlobally(state, p1, GREEN);

        for (UUID pid : List.of(p1, p2)) {
            Set<String> bar = state.boardState().sheetProgress().get(pid).rowStates().get(BAR).crossedCells();
            for (Cell c : rows.get(BAR).cells()) {
                assertEquals(c.color() == Color.GREEN, bar.contains(c.id()),
                        "player " + pid + " forfeits only the green bar cells");
            }
        }
    }

    @Test
    void closingARowForfeitsThatColourForEveryoneAndBlocksRetrigger() {
        UUID p2 = UUID.randomUUID();
        List<Row> rows = board();
        Cell redBonus = rows.get(RED).cells().get(5);
        addBonusBox(redBonus);
        // Bar: yellow first, then green. If the forfeited yellow were NOT skipped, the next bonus
        // cross would wrongly target the (now closed) yellow row; it must skip to green instead.
        rows.add(bonusBar(List.of(Color.YELLOW, Color.GREEN, Color.RED)));

        Map<UUID, SheetLayout> layouts = new HashMap<>();
        Map<UUID, SheetProgress> progresses = new HashMap<>();
        for (UUID pid : List.of(p1, p2)) {
            layouts.put(pid, new SheetLayout(rows));
            progresses.put(pid, new SheetProgress(new HashMap<>(), 0));
        }
        // p1 already has enough non-closing yellow crosses to lock the row.
        Set<String> yellowCrosses = new HashSet<>();
        for (int i = 0; i < 5; i++) yellowCrosses.add(rows.get(YELLOW).cells().get(i).id());
        progresses.get(p1).updateRowState(YELLOW, new RowState(yellowCrosses, false));
        List<Die> dice = new ArrayList<>(List.of(
                new Die(Color.WHITE, 6), new Die(Color.WHITE, 6),
                new Die(Color.RED, 6), new Die(Color.YELLOW, 6),
                new Die(Color.GREEN, 6), new Die(Color.BLUE, 6)));
        TurnState turn = new TurnState();
        turn.setActivePlayerId(p1);
        turn.setPhase(TurnPhase.ROLL);
        GameState state = new GameState(CardMode.DETERMINISTIC, List.of(p1, p2), null, layouts,
                new BoardState(progresses, dice, new HashMap<>()), turn);

        // Close yellow through the real close entry point (as the turn evaluation does).
        RowClosureEvaluator.applyRowClosure(state, YELLOW, p1, 5);

        // (1) yellow is closed, and BOTH players forfeit their yellow bonus-bar cells.
        assertTrue(state.isRowClosed(YELLOW), "yellow row is closed");
        for (UUID pid : List.of(p1, p2)) {
            Set<String> bar = state.boardState().sheetProgress().get(pid).rowStates().get(BAR).crossedCells();
            for (Cell c : rows.get(BAR).cells()) {
                if (c.color() == Color.YELLOW) {
                    assertTrue(bar.contains(c.id()), pid + " must forfeit the yellow bonus-bar cell");
                }
            }
        }

        // (2) p1 now crosses the red bonus box: the forfeited yellow cell is skipped, the chain
        //     consumes the next open (green) cell and forces a green cross — the closed yellow row
        //     is never re-triggered.
        rules.apply(state, new RollAction(p1));
        rules.apply(state, new CrossCellAction(p1, RED, redBonus.id(), DiceCombination.WHITE_WHITE));

        assertTrue(crossed(state, BAR).contains(rows.get(BAR).cells().get(1).id()),
                "the green bar cell is consumed (yellow was skipped)");
        assertEquals(1, crossed(state, GREEN).size(), "the forced cross landed in the green row");
        assertEquals(5, crossed(state, YELLOW).size(), "the closed yellow row received no forced bonus cross");
    }

    @Test
    void forcedCrossNeverCrossesTheLockCell() {
        List<Row> rows = board();
        Cell redBonus = rows.get(RED).cells().get(5);
        addBonusBox(redBonus);
        GameState state = stateWithBar(rows, List.of(Color.GREEN, Color.RED));
        // Fill the green row up to the last non-closing cell (only the lock cell remains).
        Set<String> greenCrosses = new HashSet<>();
        for (int i = 0; i < 10; i++) greenCrosses.add(rows.get(GREEN).cells().get(i).id());
        state.boardState().sheetProgress().get(p1).updateRowState(GREEN, new RowState(greenCrosses, false));
        rules.apply(state, new RollAction(p1));

        rules.apply(state, new CrossCellAction(p1, RED, redBonus.id(), DiceCombination.WHITE_WHITE));

        assertEquals(10, crossed(state, GREEN).size(), "no extra green cross — only the lock cell was left");
        assertFalse(crossed(state, GREEN).contains(rows.get(GREEN).cells().get(10).id()),
                "the forced cross must not cross the lock cell");
    }

    @Test
    void forcedCrossLandsPastExistingCrosses() {
        List<Row> rows = board();
        Cell redBonus = rows.get(RED).cells().get(5);
        addBonusBox(redBonus);
        GameState state = stateWithBar(rows, List.of(Color.GREEN, Color.RED));
        // Green already crossed through position 2 → the forced cross must land on position 3.
        Set<String> greenCrosses = new HashSet<>();
        for (int i = 0; i <= 2; i++) greenCrosses.add(rows.get(GREEN).cells().get(i).id());
        state.boardState().sheetProgress().get(p1).updateRowState(GREEN, new RowState(greenCrosses, false));
        rules.apply(state, new RollAction(p1));

        rules.apply(state, new CrossCellAction(p1, RED, redBonus.id(), DiceCombination.WHITE_WHITE));

        assertTrue(crossed(state, GREEN).contains(rows.get(GREEN).cells().get(3).id()),
                "forced cross lands on the next box after the right-most cross (position 3)");
    }

    @Test
    void consumptionSkipsForfeitedBarCells() {
        List<Row> rows = board();
        Cell redBonus = rows.get(RED).cells().get(5);
        addBonusBox(redBonus);
        // Bar: [GREEN, YELLOW, RED]; the green cell is already forfeited/crossed.
        GameState state = stateWithBar(rows, List.of(Color.GREEN, Color.YELLOW, Color.RED));
        Cell barGreen = rows.get(BAR).cells().get(0);
        state.boardState().sheetProgress().get(p1).updateRowState(BAR, new RowState(Set.of(barGreen.id()), false));
        rules.apply(state, new RollAction(p1));

        rules.apply(state, new CrossCellAction(p1, RED, redBonus.id(), DiceCombination.WHITE_WHITE));

        assertTrue(crossed(state, BAR).contains(rows.get(BAR).cells().get(1).id()),
                "consumption skips the forfeited green cell and takes the next open (yellow) one");
        assertEquals(1, crossed(state, YELLOW).size(), "the yellow row got the forced cross");
        assertEquals(0, crossed(state, GREEN).size(), "the forfeited green cell forced no cross");
    }

    @Test
    void anExhaustedBarForcesNoCross() {
        List<Row> rows = board();
        Cell redBonus = rows.get(RED).cells().get(5);
        addBonusBox(redBonus);
        GameState state = stateWithBar(rows, List.of(Color.GREEN)); // single-cell bar...
        state.boardState().sheetProgress().get(p1)
                .updateRowState(BAR, new RowState(Set.of(rows.get(BAR).cells().get(0).id()), false)); // ...already used
        rules.apply(state, new RollAction(p1));

        rules.apply(state, new CrossCellAction(p1, RED, redBonus.id(), DiceCombination.WHITE_WHITE));

        assertTrue(crossed(state, RED).contains(redBonus.id()), "the bonus box is still crossed");
        assertEquals(0, crossed(state, GREEN).size(), "an exhausted bonus bar forces no further cross");
    }

    @Test
    void factoryGeneratesBonusBoxesAndBarForLongo() {
        UUID player = UUID.randomUUID();
        List<Row> rows = new ConfigurableGameStyleFactory(
                GameSettings.builder().base(nl.adg.qwixx.game.BaseVariant.LONGO).bonusA(true).build())
                .buildRows(List.of(player)).get(player);

        List<Row> coloured = rows.stream().filter(r -> r.lock() != null && !r.isBonusBar()).toList();
        assertEquals(4, coloured.size());
        for (Row row : coloured) {
            long boxes = row.cells().stream()
                    .filter(c -> c.tags().stream().anyMatch(t -> t instanceof CellTag.BonusBox)).count();
            assertEquals(3, boxes, "each Longo coloured row has 3 bonus boxes");
            assertTrue(row.cells().stream().noneMatch(
                    c -> c.isClosingEligible() && c.tags().stream().anyMatch(t -> t instanceof CellTag.BonusBox)),
                    "no bonus box sits on a Longo lock cell");
        }
        assertEquals(12, rows.stream().filter(Row::isBonusBar).findFirst().orElseThrow().cells().size());
    }

    @Test
    void consumptionContinuesRightOfTheRightmostCross() {
        List<Row> rows = board();
        Cell greenBonus = rows.get(GREEN).cells().get(5); // green "7", crossable via WW
        addBonusBox(greenBonus);
        // Fixed bar sequence — RED sits at indices 0, 5, 8.
        GameState state = stateWithBar(rows, List.of(
                Color.RED, Color.YELLOW, Color.GREEN, Color.BLUE, Color.GREEN, Color.RED,
                Color.BLUE, Color.YELLOW, Color.RED, Color.YELLOW, Color.BLUE, Color.GREEN));
        // Close red early (nothing consumed yet): forfeits the red bar cells at 0, 5, 8 — so the
        // right-most crossed cell becomes index 8.
        RowClosureEvaluator.closeRowGlobally(state, p1, RED);
        List<Cell> bar = rows.get(BAR).cells();
        assertTrue(crossed(state, BAR).containsAll(List.of(bar.get(0).id(), bar.get(5).id(), bar.get(8).id())),
                "all red bar cells are forfeited when red closes");

        rules.apply(state, new RollAction(p1));
        rules.apply(state, new CrossCellAction(p1, GREEN, greenBonus.id(), DiceCombination.WHITE_WHITE));

        // Like a normal row, the next consumable cell is the first one to the RIGHT of the
        // right-most cross (index 8) — index 9. Cells to the left of 8 are now unavailable.
        assertTrue(crossed(state, BAR).contains(bar.get(9).id()), "consumes the next cell right of the cross (index 9)");
        assertFalse(crossed(state, BAR).contains(bar.get(1).id()), "cells left of the right-most cross stay unavailable");
        assertEquals(1, crossed(state, YELLOW).size(), "index 9 is yellow → forces one yellow cross");
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private Set<String> crossed(GameState state, int rowIndex) {
        RowState rs = state.boardState().sheetProgress().get(p1).rowStates().get(rowIndex);
        return rs != null ? rs.crossedCells() : Set.of();
    }

    private void addBonusBox(Cell cell) {
        List<CellTag> tags = new ArrayList<>(cell.tags());
        tags.add(new CellTag.BonusBox());
        cell.setTags(tags);
    }

    private List<Row> board() {
        List<Row> rows = new ArrayList<>();
        rows.add(ascendingRow(Color.RED));
        rows.add(ascendingRow(Color.YELLOW));
        rows.add(descendingRow(Color.GREEN));
        rows.add(descendingRow(Color.BLUE));
        return rows;
    }

    private Row ascendingRow(Color color) {
        Row row = new Row();
        Cell last = null;
        for (int i = 0; i < 11; i++) {
            Cell c = new Cell(i);
            c.setColor(color);
            c.setDisplayValue(String.valueOf(i + 2));
            c.setTags(new ArrayList<>());
            row.addCell(c);
            last = c;
        }
        last.setClosingEligible(true);
        row.addLock(new LockCell(UUID.randomUUID().toString(), color, 5, List.of(last.id())));
        return row;
    }

    private Row descendingRow(Color color) {
        Row row = new Row();
        Cell last = null;
        for (int i = 0; i < 11; i++) {
            Cell c = new Cell(i);
            c.setColor(color);
            c.setDisplayValue(String.valueOf(12 - i));
            c.setTags(new ArrayList<>());
            row.addCell(c);
            last = c;
        }
        last.setClosingEligible(true);
        row.addLock(new LockCell(UUID.randomUUID().toString(), color, 5, List.of(last.id())));
        return row;
    }

    private Row bonusBar(List<Color> colours) {
        Row row = new Row();
        row.setBonusBar();
        for (int i = 0; i < colours.size(); i++) {
            Cell c = new Cell(i);
            c.setColor(colours.get(i));
            c.setDisplayValue("");
            c.setTags(new ArrayList<>());
            row.addCell(c);
        }
        return row;
    }

    private GameState stateWithBar(List<Row> rows, List<Color> barColours) {
        rows.add(bonusBar(barColours)); // index BAR (4)
        Map<UUID, SheetLayout> layouts = new HashMap<>();
        Map<UUID, SheetProgress> progresses = new HashMap<>();
        layouts.put(p1, new SheetLayout(rows));
        progresses.put(p1, new SheetProgress(new HashMap<>(), 0));
        List<Die> dice = new ArrayList<>(List.of(
                new Die(Color.WHITE, 6), new Die(Color.WHITE, 6),
                new Die(Color.RED, 6), new Die(Color.YELLOW, 6),
                new Die(Color.GREEN, 6), new Die(Color.BLUE, 6)));
        BoardState board = new BoardState(progresses, dice, new HashMap<>());
        TurnState turn = new TurnState();
        turn.setActivePlayerId(p1);
        turn.setPhase(TurnPhase.ROLL);
        return new GameState(CardMode.DETERMINISTIC, List.of(p1), null, layouts, board, turn);
    }

    /** Fixed dice: white1=3, white2=4 (sum=7), red=2, yellow=3, green=4, blue=5. */
    private Random fixedRandom() {
        return new Random() {
            private final int[] seq = {2, 3, 1, 2, 3, 4};
            private int pos = 0;
            @Override public int nextInt(int bound) { return seq[pos++ % seq.length]; }
        };
    }
}
