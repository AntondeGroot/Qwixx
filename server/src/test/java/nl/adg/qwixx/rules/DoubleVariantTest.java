package nl.adg.qwixx.rules;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
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
import nl.adg.qwixx.game.factory.ConfigurableGameStyleFactory;
import nl.adg.qwixx.game.options.GameSettings;
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
 * Double A / Double B "twin" cell mechanics. Fixed dice (see {@link #fixedRandom}):
 * white1=3, white2=4 (WW sum = 7), red=2, yellow=3, green=4, blue=5.
 * The GREEN row's "7" cell (position 5) is reachable via BOTH white+white (7) and
 * white+green (3+4=7), which lets us exercise same-turn primary+twin crossing.
 */
class DoubleVariantTest {

    private static final int GREEN_ROW = 2; // [RED, YELLOW, GREEN(desc), BLUE]
    private static final int POS7 = 5;      // GREEN descending: displayValue "7" sits at position 5

    private final UUID p1 = UUID.randomUUID();
    private final StandardTurnRules rules = new StandardTurnRules(fixedRandom());

    // ── Rule behaviour ────────────────────────────────────────────────────────

    @Test
    void twinIsNotOfferedUntilItsPrimaryIsCrossed() {
        List<Row> rows = buildStandardRows();
        Cell primary = rows.get(GREEN_ROW).cells().get(POS7);
        Cell twin = addTwin(rows.get(GREEN_ROW), primary);

        GameState state = buildStateInRoll(rows, p1);
        rules.apply(state, new RollAction(p1));

        assertFalse(offered(state, twin.id()), "twin must not be offered before its primary is crossed");
        assertTrue(offered(state, primary.id()), "primary should be offered");
    }

    @Test
    void twinBecomesOfferableAndCanBeCrossedSameTurn() {
        List<Row> rows = buildStandardRows();
        Cell primary = rows.get(GREEN_ROW).cells().get(POS7);
        Cell twin = addTwin(rows.get(GREEN_ROW), primary);

        GameState state = buildStateInRoll(rows, p1);
        rules.apply(state, new RollAction(p1));

        // Cross the primary with white+white (7), leaving the colour-die slot free.
        rules.apply(state, new CrossCellAction(p1, GREEN_ROW, primary.id(), DiceCombination.WHITE_WHITE));
        assertTrue(offered(state, twin.id()), "twin should be offerable once its primary is crossed");

        // Cross the twin the same turn with white+green (3+4=7).
        rules.apply(state, new CrossCellAction(p1, GREEN_ROW, twin.id(), DiceCombination.WHITE_COLOR));

        Set<String> crossed = rowCrossed(state, p1, GREEN_ROW);
        assertTrue(crossed.contains(primary.id()), "primary must be crossed");
        assertTrue(crossed.contains(twin.id()), "twin must be crossed the same turn");
    }

    @Test
    void twinIsLockedOutOnceACellToTheRightIsCrossed() {
        // Primary crossed and it is the rightmost cross → twin offerable.
        List<Row> reachable = buildStandardRows();
        Cell primaryA = reachable.get(GREEN_ROW).cells().get(POS7);
        Cell twinA = addTwin(reachable.get(GREEN_ROW), primaryA);
        GameState offerable = buildStateInRoll(reachable, p1);
        offerable.boardState().sheetProgress().get(p1)
                .updateRowState(GREEN_ROW, new RowState(Set.of(primaryA.id()), false));
        rules.apply(offerable, new RollAction(p1));
        assertTrue(offered(offerable, twinA.id()),
                "control: twin offerable when its primary is the rightmost cross");

        // Same setup, but a cell to the primary's right is also crossed → twin locked out.
        List<Row> blocked = buildStandardRows();
        Cell primaryB = blocked.get(GREEN_ROW).cells().get(POS7);
        Cell twinB = addTwin(blocked.get(GREEN_ROW), primaryB);
        Cell toTheRight = blocked.get(GREEN_ROW).cells().get(POS7 + 1); // position 6, to the right
        GameState lockedOut = buildStateInRoll(blocked, p1);
        lockedOut.boardState().sheetProgress().get(p1)
                .updateRowState(GREEN_ROW, new RowState(Set.of(primaryB.id(), toTheRight.id()), false));
        rules.apply(lockedOut, new RollAction(p1));
        assertFalse(offered(lockedOut, twinB.id()),
                "twin must be locked out once a cell to the primary's right is crossed");
    }

    @Test
    void crossingATwinDoesNotBlockProgressionToTheRight() {
        List<Row> rows = buildStandardRows();
        Cell primary = rows.get(GREEN_ROW).cells().get(POS7);
        Cell twin = addTwin(rows.get(GREEN_ROW), primary);
        GameState state = buildStateInRoll(rows, p1);
        state.boardState().sheetProgress().get(p1)
                .updateRowState(GREEN_ROW, new RowState(Set.of(primary.id(), twin.id()), false));

        // Twin shares the primary's position, so the rightmost cross stays at the primary.
        Row row = state.sheetLayouts().get(p1).rows().get(GREEN_ROW);
        RowState rs = state.boardState().sheetProgress().get(p1).rowStates().get(GREEN_ROW);
        assertEquals(POS7, CellCrosser.rightmostCrossedPosition(row, rs),
                "a crossed twin must not advance the row's rightmost position");
    }

    // ── Scoring ─────────────────────────────────────────────────────────────

    @Test
    void twinCrossCountsAsAnExtraCrossInItsColor() {
        List<Row> rows = buildStandardRows();
        Cell primary = rows.get(GREEN_ROW).cells().get(POS7);
        Cell twin = addTwin(rows.get(GREEN_ROW), primary);

        SheetLayout layout = new SheetLayout(rows);
        Map<Integer, RowState> rowStates = new HashMap<>();
        rowStates.put(GREEN_ROW, new RowState(Set.of(primary.id(), twin.id()), false));
        SheetProgress progress = new SheetProgress(rowStates, 0);

        var score = new StandardScoringEngine().calculate(layout, progress);
        assertEquals(2, score.crossesPerColor().getOrDefault(Color.GREEN, 0),
                "primary + twin should count as two green crosses");
    }

    // ── Generation (factory) ───────────────────────────────────────────────

    @Test
    void doubleAAddsATwinToEveryNonClosingColouredCell() {
        List<Row> rows = buildForSettings(GameSettings.builder().doubleA(true).build());
        for (int r = 0; r < 4; r++) {
            long twins = rows.get(r).cells().stream().filter(c -> CellCrosser.findTwinTag(c) != null).count();
            long nonClosing = rows.get(r).cells().stream()
                    .filter(c -> CellCrosser.findTwinTag(c) == null && !c.isClosingEligible()).count();
            assertEquals(nonClosing, twins,
                    "Double A must twin every non-closing cell in row " + r);
            assertTrue(rows.get(r).cells().stream().noneMatch(
                    c -> c.isClosingEligible() && CellCrosser.findTwinTag(c) != null),
                    "the closing cell must not get a twin");
        }
    }

    @Test
    void doubleBAddsTwinsOnlyAtFixedPositions() {
        List<Row> rows = buildForSettings(GameSettings.builder().doubleB(true).build());
        for (int r = 0; r < 4; r++) {
            List<Integer> twinPositions = rows.get(r).cells().stream()
                    .filter(c -> CellCrosser.findTwinTag(c) != null)
                    .map(Cell::position).sorted().toList();
            assertEquals(List.of(2, 5, 8), twinPositions,
                    "Double B (standard) twins the fixed columns 2,5,8 in row " + r);
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private boolean offered(GameState state, String cellId) {
        for (GameAction a : rules.getValidActions(state, p1)) {
            if (a instanceof CrossCellAction cross && cross.cellId().equals(cellId)) return true;
        }
        return false;
    }

    private Cell addTwin(Row row, Cell primary) {
        Cell twin = new Cell(primary.position());
        twin.setColor(primary.color());
        twin.setDisplayValue(primary.displayValue());
        twin.setClosingEligible(false);
        twin.setTags(List.of(new CellTag.DoubleTwin(primary.id())));
        row.addCell(twin);
        return twin;
    }

    private List<Row> buildForSettings(GameSettings settings) {
        UUID player = UUID.randomUUID();
        return new ConfigurableGameStyleFactory(settings).buildRows(List.of(player)).get(player);
    }

    private Set<String> rowCrossed(GameState state, UUID playerId, int rowIndex) {
        RowState rs = state.boardState().sheetProgress().get(playerId).rowStates().get(rowIndex);
        return rs != null ? rs.crossedCells() : Set.of();
    }

    private List<Row> buildStandardRows() {
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

    private GameState buildStateInRoll(List<Row> rows, UUID... players) {
        Map<UUID, SheetLayout> layouts = new HashMap<>();
        Map<UUID, SheetProgress> progresses = new HashMap<>();
        for (UUID pid : players) {
            layouts.put(pid, new SheetLayout(rows));
            progresses.put(pid, new SheetProgress(new HashMap<>(), 0));
        }
        List<Die> dice = new ArrayList<>(List.of(
                new Die(Color.WHITE, 6), new Die(Color.WHITE, 6),
                new Die(Color.RED, 6), new Die(Color.YELLOW, 6),
                new Die(Color.GREEN, 6), new Die(Color.BLUE, 6)));
        BoardState board = new BoardState(progresses, dice, new HashMap<>());
        TurnState turn = new TurnState();
        turn.setActivePlayerId(players[0]);
        turn.setPhase(TurnPhase.ROLL);
        return new GameState(CardMode.SAME_CARDS, List.of(players), null, layouts, board, turn);
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
