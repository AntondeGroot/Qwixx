package nl.adg.qwixx.rules;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import nl.adg.qwixx.action.DeclareLockIntentAction;
import nl.adg.qwixx.action.GameAction;
import nl.adg.qwixx.data.*;
import nl.adg.qwixx.state.*;
import org.junit.jupiter.api.Test;

/**
 * Direct unit tests for the package-private static helpers of {@link RowClosureEvaluator}.
 * Lock-eligibility and closure-declaration decisions are exercised on BOTH sides of every guard
 * (enough / not enough non-closing crosses, closing cell crossed / not, row lockable / not,
 * second-to-last closing cell crossed this turn / not) with exact assertions so that
 * conditional-boundary and return-value mutants are killed.
 */
class RowClosureEvaluatorTest {

    private static final int MIN = 5;
    private final UUID p1 = UUID.randomUUID();
    private final UUID p2 = UUID.randomUUID();

    // ── rowIsNotLockable ──────────────────────────────────────────────────────

    @Test
    void rowIsNotLockable_trueWhenRowHasNoLock() {
        Row lockless = rowOf(List.of(cell(0, Color.RED), cell(1, Color.RED)), null);
        GameState state = singlePlayer(layoutOf(lockless), emptyProgress());
        assertTrue(RowClosureEvaluator.rowIsNotLockable(state, p1, 0));
    }

    @Test
    void rowIsNotLockable_trueWhenLockAlreadyCrossed() {
        GameState state = singlePlayer(layoutOf(standardRow(Color.RED)),
                progress(0, new RowState(new HashSet<>(), true)));
        assertTrue(RowClosureEvaluator.rowIsNotLockable(state, p1, 0));
    }

    @Test
    void rowIsNotLockable_trueWhenRowGloballyClosed() {
        GameState state = singlePlayer(layoutOf(standardRow(Color.RED)), emptyProgress());
        state.boardState().closedRows().put(0, p2);
        assertTrue(RowClosureEvaluator.rowIsNotLockable(state, p1, 0));
    }

    @Test
    void rowIsNotLockable_falseWhenLockablePresentOpenAndNotCrossed() {
        GameState state = singlePlayer(layoutOf(standardRow(Color.RED)), emptyProgress());
        assertFalse(RowClosureEvaluator.rowIsNotLockable(state, p1, 0),
                "a row with a lock, not yet lock-crossed and not closed, IS lockable");
    }

    // ── hasEnoughNonClosingCrosses (boundary at MIN) ──────────────────────────

    @Test
    void hasEnoughNonClosingCrosses_trueAtExactlyMin() {
        GameState state = singlePlayer(layoutOf(standardRow(Color.RED)), emptyProgress());
        Set<String> crosses = firstNonClosing(state, 5);
        assertTrue(RowClosureEvaluator.hasEnoughNonClosingCrosses(state, p1, 0, crosses, MIN),
                "5 non-closing crosses meets the minimum of 5");
    }

    @Test
    void hasEnoughNonClosingCrosses_falseOneBelowMin() {
        GameState state = singlePlayer(layoutOf(standardRow(Color.RED)), emptyProgress());
        Set<String> crosses = firstNonClosing(state, 4);
        assertFalse(RowClosureEvaluator.hasEnoughNonClosingCrosses(state, p1, 0, crosses, MIN),
                "4 non-closing crosses is one short of the minimum");
    }

    @Test
    void hasEnoughNonClosingCrosses_closingCellDoesNotCountTowardMinimum() {
        GameState state = singlePlayer(layoutOf(standardRow(Color.RED)), emptyProgress());
        Set<String> crosses = firstNonClosing(state, 4);
        crosses.add(lastClosingCell(state, 0)); // add the closing cell → still only 4 non-closing
        assertFalse(RowClosureEvaluator.hasEnoughNonClosingCrosses(state, p1, 0, crosses, MIN),
                "the closing cell must not count toward the non-closing minimum");
    }

    // ── playerHasCrossedAClosingCell ──────────────────────────────────────────

    @Test
    void playerHasCrossedAClosingCell_trueWhenClosingCellPresent() {
        GameState state = singlePlayer(layoutOf(standardRow(Color.RED)), emptyProgress());
        Set<String> crosses = new HashSet<>(Set.of(lastClosingCell(state, 0)));
        assertTrue(RowClosureEvaluator.playerHasCrossedAClosingCell(state, p1, 0, crosses));
    }

    @Test
    void playerHasCrossedAClosingCell_falseWhenNoClosingCellPresent() {
        GameState state = singlePlayer(layoutOf(standardRow(Color.RED)), emptyProgress());
        assertFalse(RowClosureEvaluator.playerHasCrossedAClosingCell(state, p1, 0, firstNonClosing(state, 3)));
    }

    // ── allCrossesForPlayer (permanent ∪ pending) ─────────────────────────────

    @Test
    void allCrossesForPlayer_unionsPermanentAndPendingCrosses() {
        String permanent = "perm-cell";
        String pending = "pending-cell";
        GameState state = singlePlayer(layoutOf(standardRow(Color.RED)),
                progress(0, new RowState(new HashSet<>(Set.of(permanent)), false)));
        putPending(state, p1, 0, pending);

        assertEquals(Set.of(permanent, pending),
                RowClosureEvaluator.allCrossesForPlayer(state, p1, 0),
                "all crosses = permanent crosses ∪ this-turn pending crosses");
    }

    // ── crossedThisTurn ───────────────────────────────────────────────────────

    @Test
    void crossedThisTurn_trueWhenCellInThisTurnPendingBuffer() {
        GameState state = singlePlayer(layoutOf(standardRow(Color.RED)), emptyProgress());
        putPending(state, p1, 0, "just-crossed");
        assertTrue(RowClosureEvaluator.crossedThisTurn(state, p1, 0, "just-crossed"));
    }

    @Test
    void crossedThisTurn_falseForCellNotInPendingBuffer() {
        GameState state = singlePlayer(layoutOf(standardRow(Color.RED)), emptyProgress());
        putPending(state, p1, 0, "some-other-cell");
        assertFalse(RowClosureEvaluator.crossedThisTurn(state, p1, 0, "just-crossed"));
    }

    @Test
    void crossedThisTurn_falseWhenNoActiveTurn() {
        GameState state = noTurn(layoutOf(standardRow(Color.RED)), emptyProgress());
        assertFalse(RowClosureEvaluator.crossedThisTurn(state, p1, 0, "anything"),
                "with no active turn there are no pending crosses");
    }

    // ── canCrossLock (composite) ──────────────────────────────────────────────

    @Test
    void canCrossLock_falseWhenRowNotLockable() {
        GameState state = singlePlayer(layoutOf(standardRow(Color.RED)),
                progress(0, new RowState(fullNonClosingPlusClosing(standardRow(Color.RED)), true))); // lockCrossed
        assertFalse(RowClosureEvaluator.canCrossLock(state, p1, 0, MIN));
    }

    @Test
    void canCrossLock_falseWithEnoughCrossesButNoClosingCell() {
        GameState state = singlePlayer(layoutOf(standardRow(Color.RED)), emptyProgress());
        setPermanent(state, 0, firstNonClosing(state, 5)); // 5 non-closing, no closing cell
        assertFalse(RowClosureEvaluator.canCrossLock(state, p1, 0, MIN),
                "5 non-closing crosses is not enough to lock without an eligible closing cell crossed");
    }

    @Test
    void canCrossLock_falseWithClosingCellButTooFewCrosses() {
        GameState state = singlePlayer(layoutOf(standardRow(Color.RED)), emptyProgress());
        Set<String> crosses = firstNonClosing(state, 4);
        crosses.add(lastClosingCell(state, 0));
        setPermanent(state, 0, crosses);
        assertFalse(RowClosureEvaluator.canCrossLock(state, p1, 0, MIN),
                "closing cell crossed but only 4 non-closing crosses → cannot lock");
    }

    @Test
    void canCrossLock_trueWithEnoughCrossesAndLastClosingCell() {
        GameState state = singlePlayer(layoutOf(standardRow(Color.RED)), emptyProgress());
        Set<String> crosses = firstNonClosing(state, 5);
        crosses.add(lastClosingCell(state, 0));
        setPermanent(state, 0, crosses);
        assertTrue(RowClosureEvaluator.canCrossLock(state, p1, 0, MIN),
                "5 non-closing crosses + the last closing cell → the lock is available");
    }

    // ── canDeclareViaNonFinalClosingCell (Longo two-closing-cell scenario) ────

    @Test
    void canDeclareViaNonFinalClosingCell_falseForSingleClosingCellRow() {
        GameState state = singlePlayer(layoutOf(standardRow(Color.RED)), emptyProgress());
        setPermanent(state, 0, firstNonClosing(state, 5));
        putPending(state, p1, 0, lastClosingCell(state, 0));
        assertFalse(RowClosureEvaluator.canDeclareViaNonFinalClosingCell(state, p1, 0, MIN),
                "a standard row with a single closing cell can never declare via a non-final closing cell");
    }

    @Test
    void canDeclareViaNonFinalClosingCell_trueWhenSecondToLastCrossedThisTurnAndCanLock() {
        GameState state = singlePlayer(layoutOf(twoClosingRow(Color.RED)), emptyProgress());
        setPermanent(state, 0, firstNonClosing(state, 5));      // 5 non-closing crosses
        putPending(state, p1, 0, secondToLastClosingCell(state, 0)); // "15" crossed THIS turn
        assertTrue(RowClosureEvaluator.canDeclareViaNonFinalClosingCell(state, p1, 0, MIN),
                "second-to-last closing cell crossed this turn + enough crosses → may declare");
    }

    @Test
    void canDeclareViaNonFinalClosingCell_falseWhenSecondToLastNotCrossedThisTurn() {
        GameState state = singlePlayer(layoutOf(twoClosingRow(Color.RED)), emptyProgress());
        Set<String> crosses = firstNonClosing(state, 5);
        crosses.add(secondToLastClosingCell(state, 0)); // crossed PERMANENTLY, not this turn
        setPermanent(state, 0, crosses);
        assertFalse(RowClosureEvaluator.canDeclareViaNonFinalClosingCell(state, p1, 0, MIN),
                "second-to-last must be crossed THIS turn (in the undo buffer) to declare");
    }

    @Test
    void canDeclareViaNonFinalClosingCell_falseWhenNotEnoughCrosses() {
        GameState state = singlePlayer(layoutOf(twoClosingRow(Color.RED)), emptyProgress());
        setPermanent(state, 0, firstNonClosing(state, 4)); // one short
        putPending(state, p1, 0, secondToLastClosingCell(state, 0));
        assertFalse(RowClosureEvaluator.canDeclareViaNonFinalClosingCell(state, p1, 0, MIN),
                "declaration still requires enough non-closing crosses to actually lock");
    }

    // ── declareLockIntentActions ──────────────────────────────────────────────

    @Test
    void declareLockIntentActions_emptyForStandardRowEvenWhenClosingCellPending() {
        GameState state = singlePlayer(layoutOf(standardRow(Color.RED)), emptyProgress());
        setPermanent(state, 0, firstNonClosing(state, 5));
        putPending(state, p1, 0, lastClosingCell(state, 0));
        assertTrue(RowClosureEvaluator.declareLockIntentActions(state, p1, MIN).isEmpty(),
                "standard single-closing rows are auto-detected, never offered as explicit declarations");
    }

    @Test
    void declareLockIntentActions_offersExactlyOneForQualifyingTwoClosingRow() {
        GameState state = singlePlayer(layoutOf(twoClosingRow(Color.RED)), emptyProgress());
        setPermanent(state, 0, firstNonClosing(state, 5));
        putPending(state, p1, 0, secondToLastClosingCell(state, 0));

        List<GameAction> actions = RowClosureEvaluator.declareLockIntentActions(state, p1, MIN);
        assertEquals(1, actions.size(), "exactly one declaration is offered for the qualifying row");
        assertEquals(new DeclareLockIntentAction(p1, 0), actions.getFirst());
    }

    @Test
    void declareLockIntentActions_emptyWhenRowAlreadyHasPendingClosure() {
        GameState state = singlePlayer(layoutOf(twoClosingRow(Color.RED)), emptyProgress());
        setPermanent(state, 0, firstNonClosing(state, 5));
        putPending(state, p1, 0, secondToLastClosingCell(state, 0));
        state.pendingClosures().put(0, new LinkedHashSet<>(Set.of(p1))); // already declared this turn
        assertTrue(RowClosureEvaluator.declareLockIntentActions(state, p1, MIN).isEmpty(),
                "no declaration offered for a row that already has a pending closure");
    }

    // ── requireMayDeclareIntent (validation guard) ────────────────────────────

    @Test
    void requireMayDeclareIntent_throwsWhenActiveDeclaresOutsideActiveMove() {
        GameState state = qualifiedTwoClosing(TurnPhase.PASSIVE_MOVE, p1);
        assertThrows(IllegalMoveException.class, () -> RowClosureEvaluator
                .requireMayDeclareIntent(state, state.turnState(), p1, 0, true, MIN));
    }

    @Test
    void requireMayDeclareIntent_okWhenActiveDeclaresInActiveMove() {
        GameState state = qualifiedTwoClosing(TurnPhase.ACTIVE_MOVE, p1);
        assertDoesNotThrow(() -> RowClosureEvaluator
                .requireMayDeclareIntent(state, state.turnState(), p1, 0, true, MIN));
    }

    @Test
    void requireMayDeclareIntent_throwsWhenPassiveInWrongPhase() {
        GameState state = qualifiedTwoClosing(TurnPhase.ROLL, p1);
        assertThrows(IllegalMoveException.class, () -> RowClosureEvaluator
                .requireMayDeclareIntent(state, state.turnState(), p1, 0, false, MIN));
    }

    @Test
    void requireMayDeclareIntent_throwsWhenPassiveNotInQueue() {
        GameState state = qualifiedTwoClosing(TurnPhase.PASSIVE_MOVE, p1);
        state.turnState().setPassivePlayerQueue(new ArrayList<>()); // p1 not queued
        assertThrows(IllegalMoveException.class, () -> RowClosureEvaluator
                .requireMayDeclareIntent(state, state.turnState(), p1, 0, false, MIN));
    }

    @Test
    void requireMayDeclareIntent_throwsWhenLockPreconditionsNotMet() {
        GameState state = qualifiedTwoClosing(TurnPhase.ACTIVE_MOVE, p1);
        // Remove the qualifying pending cross so canCrossLock becomes false.
        state.turnState().setUndoBuffer(new HashMap<>());
        setPermanent(state, 0, firstNonClosing(state, 5)); // no closing cell crossed at all
        assertThrows(IllegalMoveException.class, () -> RowClosureEvaluator
                .requireMayDeclareIntent(state, state.turnState(), p1, 0, true, MIN));
    }

    @Test
    void requireMayDeclareIntent_throwsWhenSamePlayerAlreadyDeclaredThisRow() {
        GameState state = qualifiedTwoClosing(TurnPhase.ACTIVE_MOVE, p1);
        state.pendingClosures().put(0, new LinkedHashSet<>(Set.of(p1))); // p1 already declared this row
        assertThrows(IllegalMoveException.class, () -> RowClosureEvaluator
                .requireMayDeclareIntent(state, state.turnState(), p1, 0, true, MIN));
    }

    @Test
    void requireMayDeclareIntent_allowsDifferentPlayerToAlsoDeclareTheRow() {
        // Multi-declarant: another player having declared the row must NOT block this player's declaration.
        GameState state = qualifiedTwoClosing(TurnPhase.ACTIVE_MOVE, p1);
        state.pendingClosures().put(0, new LinkedHashSet<>(Set.of(p2))); // a different player declared it
        assertDoesNotThrow(() -> RowClosureEvaluator
                .requireMayDeclareIntent(state, state.turnState(), p1, 0, true, MIN));
    }

    // ── activePlayerCouldClaimAnyPendingRow / couldActivePlayerLockRow ────────

    @Test
    void activePlayerCouldClaimAnyPendingRow_trueWhenActiveCouldStillLockADeclaredRow() {
        GameState state = twoPlayer(layoutOf(standardRow(Color.RED)));
        setPermanentFor(state, p1, 0, firstNonClosingFor(state, p1, 5)); // enough, NOT the closing cell
        state.pendingClosures().put(0, new LinkedHashSet<>(Set.of(p2))); // p2 declared it
        assertTrue(RowClosureEvaluator.activePlayerCouldClaimAnyPendingRow(state, p1, MIN),
                "active player has enough non-closing crosses and could still cross the closing cell");
    }

    @Test
    void activePlayerCouldClaimAnyPendingRow_falseWhenActiveIsTheDeclarant() {
        GameState state = twoPlayer(layoutOf(standardRow(Color.RED)));
        setPermanentFor(state, p1, 0, firstNonClosingFor(state, p1, 5));
        state.pendingClosures().put(0, new LinkedHashSet<>(Set.of(p1))); // p1 already declared it
        assertFalse(RowClosureEvaluator.activePlayerCouldClaimAnyPendingRow(state, p1, MIN),
                "the declarant is never re-invited to claim their own pending row");
    }

    @Test
    void activePlayerCouldClaimAnyPendingRow_falseWhenActiveAlreadyCrossedClosingCell() {
        GameState state = twoPlayer(layoutOf(standardRow(Color.RED)));
        Set<String> crosses = firstNonClosingFor(state, p1, 5);
        crosses.add(lastClosingCellFor(state, p1, 0));
        setPermanentFor(state, p1, 0, crosses);
        state.pendingClosures().put(0, new LinkedHashSet<>(Set.of(p2)));
        assertFalse(RowClosureEvaluator.activePlayerCouldClaimAnyPendingRow(state, p1, MIN),
                "no final look needed if the active player already crossed the closing cell");
    }

    @Test
    void activePlayerCouldClaimAnyPendingRow_falseWhenActiveLacksEnoughCrosses() {
        GameState state = twoPlayer(layoutOf(standardRow(Color.RED)));
        setPermanentFor(state, p1, 0, firstNonClosingFor(state, p1, 4)); // one short
        state.pendingClosures().put(0, new LinkedHashSet<>(Set.of(p2)));
        assertFalse(RowClosureEvaluator.activePlayerCouldClaimAnyPendingRow(state, p1, MIN));
    }

    // ── recordClosureIntent / rowHasPendingClosure ────────────────────────────

    @Test
    void recordClosureIntent_recordsDeclarantAndAddsNotification() {
        GameState state = singlePlayer(layoutOf(standardRow(Color.RED)), emptyProgress());
        RowClosureEvaluator.recordClosureIntent(state, p1, 0);
        assertEquals(Set.of(p1), state.pendingClosures().get(0), "declarant recorded for the row");
        assertEquals(1, state.closureNotifications().size(), "a closure notification is emitted");
        assertEquals(Color.RED, state.closureNotifications().getFirst().rowColor(),
                "notification carries the lock colour");
    }

    @Test
    void rowHasPendingClosure_reflectsPresenceOfPendingClosure() {
        GameState state = singlePlayer(layoutOf(standardRow(Color.RED)), emptyProgress());
        assertFalse(RowClosureEvaluator.rowHasPendingClosure(state, 0));
        state.pendingClosures().put(0, new LinkedHashSet<>(Set.of(p1)));
        assertTrue(RowClosureEvaluator.rowHasPendingClosure(state, 0));
    }

    // ── markLockCrossed ───────────────────────────────────────────────────────

    @Test
    void markLockCrossed_setsFlagWithoutLosingExistingCrosses() {
        GameState state = singlePlayer(layoutOf(standardRow(Color.RED)),
                progress(0, new RowState(new HashSet<>(Set.of("a", "b")), false)));
        RowClosureEvaluator.markLockCrossed(state, p1, 0);
        RowState rs = state.sheetProgress(p1).rowStates().get(0);
        assertTrue(rs.lockCrossed(), "lock flag is set");
        assertEquals(Set.of("a", "b"), rs.crossedCells(), "existing crosses are preserved");
    }

    // ── closeRowGlobally ──────────────────────────────────────────────────────

    @Test
    void closeRowGlobally_marksRowClosedAndRemovesThatColoursDie() {
        GameState state = singlePlayer(layoutOf(standardRow(Color.RED)), emptyProgress());
        assertTrue(state.boardState().activeDice().stream().anyMatch(d -> d.color() == Color.RED),
                "precondition: the red die is present");
        RowClosureEvaluator.closeRowGlobally(state, p1, 0);
        assertEquals(p1, state.boardState().closedRows().get(0), "row recorded as closed by p1");
        assertFalse(state.boardState().activeDice().stream().anyMatch(d -> d.color() == Color.RED),
                "the locked colour's die is removed from play");
    }

    // ── applyRowClosure ───────────────────────────────────────────────────────

    @Test
    void applyRowClosure_closesRowAndMarksDeclarantLockCrossed() {
        GameState state = twoPlayer(layoutOf(standardRow(Color.RED)));
        setPermanentFor(state, p1, 0, closingCrosses(state, p1, 0)); // p1 qualifies
        RowClosureEvaluator.applyRowClosure(state, 0, Set.of(p1), MIN);
        assertTrue(state.isRowClosed(0), "row is closed");
        assertTrue(state.sheetProgress(p1).rowStates().get(0).lockCrossed(),
                "the declarant is marked lock-crossed");
    }

    @Test
    void applyRowClosure_alsoMarksOtherQualifyingPlayer() {
        GameState state = twoPlayer(layoutOf(standardRow(Color.RED)));
        setPermanentFor(state, p1, 0, closingCrosses(state, p1, 0));
        setPermanentFor(state, p2, 0, closingCrosses(state, p2, 0)); // p2 also qualifies
        RowClosureEvaluator.applyRowClosure(state, 0, Set.of(p1), MIN);
        assertTrue(state.sheetProgress(p2).rowStates().get(0).lockCrossed(),
                "a non-declarant who also qualifies is marked lock-crossed too");
    }

    @Test
    void applyRowClosure_doesNotMarkNonQualifyingPlayer() {
        GameState state = twoPlayer(layoutOf(standardRow(Color.RED)));
        setPermanentFor(state, p1, 0, closingCrosses(state, p1, 0));
        setPermanentFor(state, p2, 0, firstNonClosingFor(state, p2, 3)); // p2 does NOT qualify
        RowClosureEvaluator.applyRowClosure(state, 0, Set.of(p1), MIN);
        RowState p2Row = state.sheetProgress(p2).rowStates().get(0);
        assertFalse(p2Row != null && p2Row.lockCrossed(),
                "a player who does not qualify is not marked lock-crossed");
    }

    @Test
    void applyRowClosure_marksEveryDeclarantEvenIfNoLongerLockEligible() {
        // Reproduces the Longo closing-race bug: p2 crossed the second-to-last closing cell ("15") this
        // turn and declared the closure, but that cross was cleared when p2's turn ended. p2 is no longer
        // lock-eligible (enough crosses but no closing cell now), yet — being a recorded declarant of the
        // row — must still get the lock cross when the row closes alongside the other declarant p1.
        GameState state = twoPlayer(layoutOf(standardRow(Color.RED)));
        setPermanentFor(state, p1, 0, closingCrosses(state, p1, 0)); // p1 qualifies live
        setPermanentFor(state, p2, 0, firstNonClosingFor(state, p2, 5)); // enough crosses, no closing cell

        RowClosureEvaluator.applyRowClosure(state, 0, new LinkedHashSet<>(List.of(p1, p2)), MIN);

        assertTrue(state.sheetProgress(p2).rowStates().get(0).lockCrossed(),
                "a declarant gets the lock cross even if no longer lock-eligible");
    }

    @Test
    void applyRowClosure_noOpWhenRowAlreadyClosed() {
        GameState state = twoPlayer(layoutOf(standardRow(Color.RED)));
        state.boardState().closedRows().put(0, p2);
        setPermanentFor(state, p1, 0, closingCrosses(state, p1, 0));
        RowClosureEvaluator.applyRowClosure(state, 0, Set.of(p1), MIN);
        assertEquals(p2, state.boardState().closedRows().get(0),
                "an already-closed row keeps its original closer and p1 is not marked");
        RowState p1Row = state.sheetProgress(p1).rowStates().get(0);
        assertFalse(p1Row != null && p1Row.lockCrossed());
    }

    // ── layout / state builders ───────────────────────────────────────────────

    private static Cell cell(int position, Color color) {
        Cell c = new Cell(position);
        c.setColor(color);
        c.setDisplayValue(String.valueOf(position + 2));
        c.setTags(List.of());
        return c;
    }

    private static Row rowOf(List<Cell> cells, LockCell lock) {
        Row row = new Row();
        for (Cell c : cells) row.addCell(c);
        if (lock != null) row.addLock(lock);
        return row;
    }

    /** 11-cell ascending row whose single last cell (position 10) is the closing cell. */
    private static Row standardRow(Color color) {
        return closingRow(color, 1);
    }

    /** 11-cell ascending row whose two last cells (positions 9 and 10) are closing cells. */
    private static Row twoClosingRow(Color color) {
        return closingRow(color, 2);
    }

    private static Row closingRow(Color color, int numClosing) {
        List<Cell> cells = new ArrayList<>();
        for (int i = 0; i < 11; i++) cells.add(cell(i, color));
        List<String> closing = new ArrayList<>();
        for (int i = 11 - numClosing; i < 11; i++) {
            cells.get(i).setClosingEligible(true);
            closing.add(cells.get(i).id());
        }
        LockCell lock = new LockCell(UUID.randomUUID().toString(), color, MIN, closing);
        return rowOf(cells, lock);
    }

    private static SheetLayout layoutOf(Row row) {
        return new SheetLayout(new ArrayList<>(List.of(row)));
    }

    private static SheetProgress emptyProgress() {
        return new SheetProgress(new HashMap<>(), 0);
    }

    private static SheetProgress progress(int rowIndex, RowState rowState) {
        Map<Integer, RowState> states = new HashMap<>();
        states.put(rowIndex, rowState);
        return new SheetProgress(states, 0);
    }

    private GameState singlePlayer(SheetLayout layout, SheetProgress progress) {
        return build(List.of(p1), Map.of(p1, layout), mutableProgress(Map.of(p1, progress)),
                TurnPhase.ACTIVE_MOVE, p1);
    }

    private GameState twoPlayer(SheetLayout layout) {
        Map<UUID, SheetLayout> layouts = new HashMap<>();
        layouts.put(p1, layout);
        layouts.put(p2, deepCopyLayout(layout));
        Map<UUID, SheetProgress> progresses = new HashMap<>();
        progresses.put(p1, emptyProgress());
        progresses.put(p2, emptyProgress());
        return build(List.of(p1, p2), layouts, progresses, TurnPhase.ACTIVE_MOVE, p1);
    }

    /** State with no active turn at all (turnState == null). */
    private GameState noTurn(SheetLayout layout, SheetProgress progress) {
        Map<UUID, SheetProgress> progresses = new HashMap<>();
        progresses.put(p1, progress);
        BoardState board = new BoardState(progresses, dice(), new HashMap<>());
        return new GameState(CardMode.SAME_CARDS, List.of(p1), null, Map.of(p1, layout), board, null);
    }

    private GameState build(List<UUID> players, Map<UUID, SheetLayout> layouts,
                            Map<UUID, SheetProgress> progresses, TurnPhase phase, UUID active) {
        BoardState board = new BoardState(progresses, dice(), new HashMap<>());
        TurnState turn = new TurnState();
        turn.setActivePlayerId(active);
        turn.setPhase(phase);
        turn.setPassivePlayerQueue(new ArrayList<>(
                players.stream().filter(pl -> !pl.equals(active)).toList()));
        turn.setUndoBuffer(new HashMap<>());
        return new GameState(CardMode.SAME_CARDS, players, null, layouts, board, turn);
    }

    private static Map<UUID, SheetProgress> mutableProgress(Map<UUID, SheetProgress> src) {
        return new HashMap<>(src);
    }

    private static List<Die> dice() {
        return new ArrayList<>(List.of(
                new Die(Color.WHITE, 6), new Die(Color.WHITE, 6),
                new Die(Color.RED, 6), new Die(Color.YELLOW, 6),
                new Die(Color.GREEN, 6), new Die(Color.BLUE, 6)));
    }

    /** Same layout instance reused (rows are immutable identifiers for these tests). */
    private static SheetLayout deepCopyLayout(SheetLayout layout) {
        return new SheetLayout(new ArrayList<>(layout.rows()));
    }

    // ── two-closing qualified-state convenience ───────────────────────────────

    private GameState qualifiedTwoClosing(TurnPhase phase, UUID active) {
        GameState state = build(List.of(active), Map.of(active, layoutOf(twoClosingRow(Color.RED))),
                mutableProgress(Map.of(active, emptyProgress())), phase, active);
        setPermanent(state, 0, firstNonClosing(state, 5));
        putPending(state, active, 0, secondToLastClosingCell(state, 0));
        return state;
    }

    // ── cross-set helpers (operate on p1 unless a player is given) ─────────────

    private Set<String> firstNonClosing(GameState state, int count) {
        return firstNonClosingFor(state, p1, count);
    }

    private Set<String> firstNonClosingFor(GameState state, UUID playerId, int count) {
        Row row = state.sheetLayout(playerId).rows().getFirst();
        Set<String> closing = new HashSet<>(row.lock().closingCells());
        Set<String> crosses = new HashSet<>();
        for (Cell c : row.cells()) {
            if (crosses.size() >= count) break;
            if (!closing.contains(c.id())) crosses.add(c.id());
        }
        return crosses;
    }

    /** 5 non-closing crosses + the last closing cell (enough to actually lock). */
    private Set<String> closingCrosses(GameState state, UUID playerId, int rowIndex) {
        Set<String> crosses = firstNonClosingFor(state, playerId, 5);
        crosses.add(lastClosingCellFor(state, playerId, rowIndex));
        return crosses;
    }

    private Set<String> fullNonClosingPlusClosing(Row row) {
        Set<String> closing = new HashSet<>(row.lock().closingCells());
        Set<String> crosses = new HashSet<>();
        for (Cell c : row.cells()) if (!closing.contains(c.id())) crosses.add(c.id());
        crosses.addAll(closing);
        return crosses;
    }

    private String lastClosingCell(GameState state, int rowIndex) {
        return lastClosingCellFor(state, p1, rowIndex);
    }

    private String lastClosingCellFor(GameState state, UUID playerId, int rowIndex) {
        List<String> closing = state.sheetLayout(playerId).rows().get(rowIndex).lock().closingCells();
        return closing.get(closing.size() - 1);
    }

    private String secondToLastClosingCell(GameState state, int rowIndex) {
        List<String> closing = state.sheetLayout(p1).rows().get(rowIndex).lock().closingCells();
        return closing.get(closing.size() - 2);
    }

    private void setPermanent(GameState state, int rowIndex, Set<String> crosses) {
        setPermanentFor(state, p1, rowIndex, crosses);
    }

    private void setPermanentFor(GameState state, UUID playerId, int rowIndex, Set<String> crosses) {
        state.sheetProgress(playerId).updateRowState(rowIndex, new RowState(new HashSet<>(crosses), false));
    }

    private void putPending(GameState state, UUID playerId, int rowIndex, String cellId) {
        state.turnState().undoBuffer()
                .computeIfAbsent(playerId, k -> new HashMap<>())
                .computeIfAbsent(rowIndex, k -> new HashSet<>())
                .add(cellId);
    }
}
