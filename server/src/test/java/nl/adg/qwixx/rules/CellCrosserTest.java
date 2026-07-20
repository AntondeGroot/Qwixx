package nl.adg.qwixx.rules;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import nl.adg.qwixx.data.*;
import nl.adg.qwixx.state.*;
import org.junit.jupiter.api.Test;

/**
 * Direct unit tests for the package-private static helpers of {@link CellCrosser}.
 * Each test exercises BOTH sides of a decision (reachable / not reachable, twin ready / not ready,
 * prerequisite met / not met) and asserts the exact returned value so that conditional-boundary,
 * negated-conditional and return-value mutants are killed.
 */
class CellCrosserTest {

    // ── isReachableCell ───────────────────────────────────────────────────────

    @Test
    void isReachableCell_trueWhenBeyondRightmostAndNotCrossed() {
        Cell c = cell(5, Color.RED, "7");
        assertTrue(CellCrosser.isReachableCell(c, 4, Set.of()),
                "a cell to the right of the right-most cross that is not itself crossed is reachable");
    }

    @Test
    void isReachableCell_falseWhenExactlyAtRightmost() {
        Cell c = cell(5, Color.RED, "7");
        assertFalse(CellCrosser.isReachableCell(c, 5, Set.of()),
                "a cell at the right-most position is NOT reachable (strictly greater required)");
    }

    @Test
    void isReachableCell_falseWhenLeftOfRightmost() {
        assertFalse(CellCrosser.isReachableCell(cell(3, Color.RED, "5"), 5, Set.of()),
                "a cell left of the right-most cross is not reachable");
    }

    @Test
    void isReachableCell_falseWhenAlreadyCrossed() {
        Cell c = cell(6, Color.RED, "8");
        assertFalse(CellCrosser.isReachableCell(c, 4, Set.of(c.id())),
                "an already-crossed cell is never reachable even if it is right of the cross");
    }

    // ── findXChangeTag ────────────────────────────────────────────────────────

    @Test
    void findXChangeTag_nullCellReturnsNull() {
        assertNull(CellCrosser.findXChangeTag(null));
    }

    @Test
    void findXChangeTag_returnsTheTagWhenPresent() {
        CellTag.XChange xc = new CellTag.XChange(9, 7);
        assertSame(xc, CellCrosser.findXChangeTag(cell(0, Color.BLUE, "", xc)));
    }

    @Test
    void findXChangeTag_nullWhenNoSuchTag() {
        assertNull(CellCrosser.findXChangeTag(cell(0, Color.RED, "5")));
    }

    // ── findTwinTag ───────────────────────────────────────────────────────────

    @Test
    void findTwinTag_nullCellReturnsNull() {
        assertNull(CellCrosser.findTwinTag(null));
    }

    @Test
    void findTwinTag_returnsTheTagWhenPresent() {
        CellTag.DoubleTwin dt = new CellTag.DoubleTwin("primary-id");
        assertSame(dt, CellCrosser.findTwinTag(cell(0, Color.RED, "3", dt)));
    }

    @Test
    void findTwinTag_nullWhenNoSuchTag() {
        assertNull(CellCrosser.findTwinTag(cell(0, Color.RED, "5")));
    }

    // ── rightmostCrossedPosition ──────────────────────────────────────────────

    @Test
    void rightmostCrossedPosition_minusOneWhenNothingCrossed() {
        Row row = rowOf(cell(0, Color.RED, "2"), cell(1, Color.RED, "3"));
        assertEquals(-1, CellCrosser.rightmostCrossedPosition(row, new RowState(Set.of(), false)));
    }

    @Test
    void rightmostCrossedPosition_returnsHighestCrossedPosition() {
        Cell c0 = cell(0, Color.RED, "2");
        Cell c1 = cell(1, Color.RED, "3");
        Cell c2 = cell(2, Color.RED, "4");
        Row row = rowOf(c0, c1, c2);
        // Cross the non-contiguous c0 and c2 → right-most is position 2.
        assertEquals(2, CellCrosser.rightmostCrossedPosition(row,
                new RowState(new HashSet<>(Set.of(c0.id(), c2.id())), false)));
    }

    // ── getRowState ───────────────────────────────────────────────────────────

    @Test
    void getRowState_returnsEmptyDefaultWhenAbsent() {
        RowState rs = CellCrosser.getRowState(new SheetProgress(new HashMap<>(), 0), 3);
        assertTrue(rs.crossedCells().isEmpty(), "absent row yields an empty cross set");
        assertFalse(rs.lockCrossed(), "absent row's lock is not crossed");
    }

    @Test
    void getRowState_returnsStoredStateWhenPresent() {
        Map<Integer, RowState> states = new HashMap<>();
        RowState stored = new RowState(new HashSet<>(Set.of("x")), true);
        states.put(2, stored);
        assertSame(stored, CellCrosser.getRowState(new SheetProgress(states, 0), 2));
    }

    // ── twinReachable ─────────────────────────────────────────────────────────

    @Test
    void twinReachable_falseWhenTwinAlreadyCrossed() {
        TwinRow r = new TwinRow();
        assertFalse(CellCrosser.twinReachable(r.row, crossedOf(r.twin.id(), r.primary.id()), r.twin),
                "an already-crossed twin is not reachable");
    }

    @Test
    void twinReachable_falseWhenCellHasNoTwinTag() {
        TwinRow r = new TwinRow();
        assertFalse(CellCrosser.twinReachable(r.row, crossedOf(r.primary.id()), r.primary),
                "a cell without a DoubleTwin tag is never twin-reachable");
    }

    @Test
    void twinReachable_falseWhenPrimaryNotCrossed() {
        TwinRow r = new TwinRow();
        assertFalse(CellCrosser.twinReachable(r.row, crossedOf(), r.twin),
                "the twin is not reachable while its primary is uncrossed");
    }

    @Test
    void twinReachable_trueWhenPrimaryIsTheRightmostCross() {
        TwinRow r = new TwinRow();
        assertTrue(CellCrosser.twinReachable(r.row, crossedOf(r.primary.id()), r.twin),
                "the twin is reachable when its primary is crossed and is the row's right-most cross");
    }

    @Test
    void twinReachable_falseWhenPrimaryIsNotTheRightmostCross() {
        TwinRow r = new TwinRow();
        // Cross the primary AND a cell to its right → primary is no longer the right-most cross.
        assertFalse(CellCrosser.twinReachable(r.row, crossedOf(r.primary.id(), r.beyond.id()), r.twin),
                "the twin is blocked once a cell to the right of the primary is crossed");
    }

    @Test
    void twinReachable_falseWhenPrimaryMissingFromRow() {
        Cell orphanTwin = cell(1, Color.RED, "3", new CellTag.DoubleTwin("does-not-exist"));
        Row row = rowOf(cell(0, Color.RED, "2"), orphanTwin);
        assertFalse(CellCrosser.twinReachable(row, crossedOf(), orphanTwin),
                "a twin whose primary is not present in the row is not reachable");
    }

    // ── bonusPrerequisiteMet ──────────────────────────────────────────────────

    @Test
    void bonusPrerequisiteMet_falseWhenStartProgressNull() {
        BonusLayout b = new BonusLayout(0, -1);
        assertFalse(CellCrosser.bonusPrerequisiteMet(b.layout, null, b.bonusRow, b.bonusCell),
                "without a pre-turn snapshot the prerequisite can never be met");
    }

    @Test
    void bonusPrerequisiteMet_trueWhenValueCrossedInUpperNeighbour() {
        BonusLayout b = new BonusLayout(0, -1);
        SheetProgress snap = progressWith(0, b.neighbourCell.id());
        assertTrue(CellCrosser.bonusPrerequisiteMet(b.layout, snap, b.bonusRow, b.bonusCell),
                "prerequisite met when the display value is permanently crossed in the upper neighbour");
    }

    @Test
    void bonusPrerequisiteMet_trueWhenValueCrossedInLowerNeighbour() {
        BonusLayout b = new BonusLayout(-1, 0); // upper skipped (negative), lower = row 0
        SheetProgress snap = progressWith(0, b.neighbourCell.id());
        assertTrue(CellCrosser.bonusPrerequisiteMet(b.layout, snap, b.bonusRow, b.bonusCell),
                "prerequisite met via the lower neighbour; a negative neighbour index is skipped safely");
    }

    @Test
    void bonusPrerequisiteMet_falseWhenNeighbourValueNotCrossed() {
        BonusLayout b = new BonusLayout(0, -1);
        SheetProgress snap = new SheetProgress(new HashMap<>(), 0); // nothing crossed
        assertFalse(CellCrosser.bonusPrerequisiteMet(b.layout, snap, b.bonusRow, b.bonusCell),
                "prerequisite not met when the value is not crossed in any neighbour");
    }

    // ── findCellById ──────────────────────────────────────────────────────────

    @Test
    void findCellById_returnsRowIndexAndCellWhenFound() {
        Cell target = cell(2, Color.GREEN, "4");
        Row row0 = rowOf(cell(0, Color.RED, "2"));
        Row row1 = rowOf(cell(0, Color.GREEN, "12"), cell(1, Color.GREEN, "11"), target);
        SheetLayout layout = new SheetLayout(List.of(row0, row1));

        var found = CellCrosser.findCellById(layout, target.id());
        assertTrue(found.isPresent());
        assertEquals(1, found.get().getKey(), "row index of the found cell");
        assertSame(target, found.get().getValue());
    }

    @Test
    void findCellById_emptyWhenNotFound() {
        SheetLayout layout = new SheetLayout(List.of(rowOf(cell(0, Color.RED, "2"))));
        assertTrue(CellCrosser.findCellById(layout, "no-such-id").isEmpty());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static Cell cell(int position, Color color, String displayValue, CellTag... tags) {
        Cell c = new Cell(position);
        c.setColor(color);
        c.setDisplayValue(displayValue);
        c.setTags(List.of(tags));
        return c;
    }

    private static Row rowOf(Cell... cells) {
        Row row = new Row();
        for (Cell c : cells) row.addCell(c);
        return row;
    }

    private static RowState crossedOf(String... ids) {
        return new RowState(new HashSet<>(Arrays.asList(ids)), false);
    }

    private static SheetProgress progressWith(int rowIndex, String crossedCellId) {
        Map<Integer, RowState> states = new HashMap<>();
        states.put(rowIndex, new RowState(new HashSet<>(Set.of(crossedCellId)), false));
        return new SheetProgress(states, 0);
    }

    /** A row containing a normal primary cell, its twin, and a cell positioned beyond the primary. */
    private static final class TwinRow {
        final Cell primary = cell(1, Color.RED, "3");
        final Cell beyond  = cell(2, Color.RED, "4");
        final Cell twin    = cell(3, Color.RED, "3", new CellTag.DoubleTwin(""));
        final Row row;

        TwinRow() {
            // The twin references its primary by id (only known after primary construction).
            twin.setTags(List.of(new CellTag.DoubleTwin(primary.id())));
            row = rowOf(cell(0, Color.RED, "2"), primary, beyond, twin);
        }
    }

    /** A layout whose row 1 is a bonus row referencing row 0 (a coloured neighbour). */
    private static final class BonusLayout {
        final Cell neighbourCell = cell(5, Color.RED, "7");
        final Cell bonusCell     = cell(0, Color.RED, "7"); // same display value "7"
        final Row bonusRow;
        final SheetLayout layout;

        BonusLayout(int upper, int lower) {
            Row neighbour = rowOf(cell(0, Color.RED, "2"), neighbourCell);
            bonusRow = rowOf(bonusCell);
            bonusRow.setBonusRow(upper, lower);
            layout = new SheetLayout(List.of(neighbour, bonusRow));
        }
    }
}
