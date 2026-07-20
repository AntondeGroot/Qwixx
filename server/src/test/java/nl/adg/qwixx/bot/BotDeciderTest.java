package nl.adg.qwixx.bot;

import static nl.adg.qwixx.bot.BotDecider.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import nl.adg.qwixx.action.CrossCellAction;
import nl.adg.qwixx.action.DeclareLockIntentAction;
import nl.adg.qwixx.action.DiceCombination;
import nl.adg.qwixx.action.EndTurnAction;
import nl.adg.qwixx.action.GameAction;
import nl.adg.qwixx.action.RollAction;
import nl.adg.qwixx.action.TakePunishmentAction;
import nl.adg.qwixx.data.Cell;
import nl.adg.qwixx.data.CellTag;
import nl.adg.qwixx.data.Color;
import nl.adg.qwixx.data.LockCell;
import nl.adg.qwixx.data.Row;
import nl.adg.qwixx.state.ActiveTurnState;
import nl.adg.qwixx.state.BoardState;
import nl.adg.qwixx.state.CardMode;
import nl.adg.qwixx.state.GameState;
import nl.adg.qwixx.state.RowState;
import nl.adg.qwixx.state.SheetLayout;
import nl.adg.qwixx.state.SheetProgress;
import nl.adg.qwixx.state.TurnPhase;
import nl.adg.qwixx.state.TurnState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BotDeciderTest {

    // Seed the tie-break RNG so a decision among equally-good moves is reproducible across runs.
    @BeforeEach
    void seedRng() {
        seedForTest(1);
    }

    // Standard mode: d6+d6, most common sum = 7, range 2–12
    private static final int STD   = 7;
    // Longo mode:    d8+d8, most common sum = 9, range 2–16
    private static final int LONGO = 9;

    // Shorthand: no previous skips in the row
    private static double loss(int gap, int displayValue, int diceCenter, boolean nearLock) {
        return BotDecider.loss(gap, 0, displayValue, diceCenter, nearLock);
    }

    // ── Gap penalty ──────────────────────────────────────────────────────────

    @Test
    void no_gap_beats_same_value_with_gap() {
        assertTrue(loss(0, 7, STD, false) < loss(1, 7, STD, false),
                "Adjacent cell must have lower loss than one cell skipped");
    }

    @Test
    void gap_penalty_grows_faster_than_linearly() {
        // gap^1.5 means doubling the gap more than doubles the penalty
        double penalty1 = loss(1, 7, STD, false);
        double penalty2 = loss(2, 7, STD, false);
        double penalty4 = loss(4, 7, STD, false);
        assertTrue(penalty2 > 2 * penalty1, "Doubling the gap must more than double the penalty");
        assertTrue(penalty4 > 2 * penalty2, "Each doubling must be progressively more expensive");
    }

    // ── Rarity bonus ─────────────────────────────────────────────────────────

    @Test
    void rare_value_beats_common_at_equal_gap_and_moderate_gap() {
        assertTrue(loss(0, 12, STD, false) < loss(0, 4, STD, false),
                "Rare 12 must beat 4 at equal gap=0");
        assertTrue(loss(2, 12, STD, false) < loss(0, 4, STD, false),
                "Rare 12 (gap=2) must still beat adjacent 4 (gap=0)");
    }

    @Test
    void rare_value_12_beats_adjacent_7_up_to_crossover_gap() {
        // gap=3: 12 wins; gap=4: adjacent 7 wins (crossover is between 3 and 4)
        assertTrue(loss(3, 12, STD, false) < loss(0, 7, STD, false),
                "Rare 12 (gap=3) must beat adjacent 7 (gap=0)");
        assertTrue(loss(4, 12, STD, false) > loss(0, 7, STD, false),
                "Rare 12 (gap=4) must lose to adjacent 7 (gap=0)");
    }

    @Test
    void most_common_value_has_no_rarity_reduction() {
        assertEquals(0.0, loss(0, 7, STD, false));
    }

    @Test
    void extremes_are_equally_rare_in_standard() {
        assertEquals(loss(0, 2, STD, false), loss(0, 12, STD, false),
                "Values 2 and 12 are equidistant from 7 and must have equal loss");
    }

    // ── Longo mode (d8+d8, center=9) ─────────────────────────────────────────

    @Test
    void longo_extremes_are_equally_rare() {
        assertEquals(loss(0, 2, LONGO, false), loss(0, 16, LONGO, false),
                "Values 2 and 16 are equidistant from 9 in Longo");
    }

    @Test
    void longo_9_is_most_common_has_no_rarity_bonus() {
        assertEquals(0.0, loss(0, 9, LONGO, false));
    }

    @Test
    void longo_rare_16_beats_adjacent_4_same_gap() {
        assertTrue(loss(0, 16, LONGO, false) < loss(0, 4, LONGO, false),
                "Rare 16 must beat 4 when both have gap=0 in Longo");
    }

    // ── Lock bonus ───────────────────────────────────────────────────────────

    @Test
    void lock_bonus_reduces_loss() {
        assertTrue(loss(0, 7, STD, true) < loss(0, 7, STD, false),
                "Near-lock must reduce loss");
    }

    @Test
    void lock_bonus_can_make_gapped_cell_beat_adjacent_common_cell() {
        double lossLock   = loss(1, 7, STD, true);
        double lossNoLock = loss(0, 7, STD, false);
        assertTrue(lossLock < lossNoLock);
    }

    // ── Previously skipped cells ─────────────────────────────────────────────

    @Test
    void previously_skipped_cells_make_new_skips_more_expensive() {
        // gap=1, no prior skips
        double fresh  = BotDecider.loss(1, 0, 7, STD, false);
        // gap=1, already skipped 3 cells in this row
        double damaged = BotDecider.loss(1, 3, 7, STD, false);
        assertTrue(damaged > fresh,
                "Skipping 1 cell in an already-skipped row must cost more than skipping in a fresh row");
    }

    @Test
    void gap_zero_is_free_regardless_of_previous_skips() {
        double noHistory  = BotDecider.loss(0, 0, 7, STD, false);
        double withHistory = BotDecider.loss(0, 5, 7, STD, false);
        assertEquals(noHistory, withHistory,
                "Adjacent cross (gap=0) must never be penalised regardless of row history");
    }

    @Test
    void accumulated_skips_eventually_exceed_passive_threshold_for_small_gap() {
        // With enough prior skips, even a 1-cell gap exceeds the passive threshold
        double l = BotDecider.loss(1, 5, 7, STD, false);
        assertTrue(l > PASSIVE_THRESHOLD,
                "gap=1 after 5 previously skipped cells must exceed passive threshold");
    }

    // ── Punishment threshold ─────────────────────────────────────────────────

    @Test
    void common_value_with_large_gap_exceeds_punishment_threshold() {
        double l = loss(5, 7, STD, false);
        assertTrue(l > PUNISHMENT_LOSS,
                "Common value with 5-cell gap must exceed punishment threshold");
    }

    @Test
    void rare_value_stays_within_punishment_threshold_despite_large_gap() {
        double l = loss(5, 12, STD, false);
        assertTrue(l <= PUNISHMENT_LOSS,
                "Rare value 12 with 5-cell gap must stay within punishment threshold");
    }

    @Test
    void rare_12_with_moderate_gap_stays_within_punishment_threshold() {
        assertTrue(loss(5, 12, STD, false) <= PUNISHMENT_LOSS,
                "Rare value 12 with 5-cell gap must stay within active-player punishment threshold");
    }

    // ── Passive threshold ────────────────────────────────────────────────────

    @Test
    void passive_player_crosses_adjacent_cell_of_any_value() {
        assertTrue(loss(0,  7, STD, false) <= PASSIVE_THRESHOLD, "Adjacent value-7 must be crossed passively");
        assertTrue(loss(0, 12, STD, false) <= PASSIVE_THRESHOLD, "Adjacent value-12 must be crossed passively");
        assertTrue(loss(0,  2, STD, false) <= PASSIVE_THRESHOLD, "Adjacent value-2 must be crossed passively");
    }

    @Test
    void passive_player_passes_rather_than_skipping_cells_for_common_value() {
        assertTrue(loss(1, 7, STD, false) > PASSIVE_THRESHOLD,
                "Passive must pass rather than skip 1 cell for common value 7");
        assertTrue(loss(4, 7, STD, false) > PASSIVE_THRESHOLD,
                "Passive must pass rather than skip 4 cells for common value 7");
    }

    @Test
    void passive_player_crosses_rare_value_with_small_gap() {
        assertTrue(loss(1, 12, STD, false) <= PASSIVE_THRESHOLD,
                "Passive should cross rare value 12 even with 1-cell gap");
    }

    @Test
    void red_11_from_scratch_exceeds_both_thresholds() {
        // Bug regression: bot crossed Red 11 as its first cross in the row (gap=9).
        // Locking the row at position 9 permanently forfeits values 2–10.
        double l = loss(9, 11, STD, false);
        assertTrue(l > PASSIVE_THRESHOLD, "Passive bot must not cross Red 11 as first cross (gap=9)");
        assertTrue(l > PUNISHMENT_LOSS,   "Active bot must prefer punishment over Red 11 as first cross");
    }

    @Test
    void passive_threshold_is_stricter_than_punishment_threshold() {
        assertTrue(PASSIVE_THRESHOLD < PUNISHMENT_LOSS,
                "Passive players must be more conservative than the active-player punishment threshold");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // decide(...) behavioural tests — pin the SPECIFIC move a bot chooses so that
    // flipping any single scoring/ordering line changes which action is returned.
    // ══════════════════════════════════════════════════════════════════════════

    private static final UUID BOT   = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID RIVAL = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private enum Mode { PASSIVE, ACTIVE_SINGLE, ACTIVE_TWO }

    // ── Action-priority: Roll and DeclareLockIntent short-circuit everything ──

    @Test
    void roll_action_is_returned_before_any_scoring() {
        // decide must return the RollAction outright (and the 3-arg overload must not return null).
        GameState state = buildState(Mode.ACTIVE_SINGLE, layout(stdRow(Color.RED)), emptyProg());
        Row r = state.sheetLayout(BOT).rows().get(0);
        List<GameAction> actions = List.of(
                new CrossCellAction(BOT, 0, r.cells().get(0).id(), DiceCombination.WHITE_WHITE),
                new RollAction(BOT));
        GameAction chosen = BotDecider.decide(state, BOT, actions);   // 3-arg overload
        assertInstanceOf(RollAction.class, chosen, "RollAction must take absolute priority");
    }

    @Test
    void declare_lock_intent_is_returned_before_any_cross() {
        GameState state = buildState(Mode.ACTIVE_SINGLE, layout(stdRow(Color.RED)), emptyProg());
        Row r = state.sheetLayout(BOT).rows().get(0);
        List<GameAction> actions = new ArrayList<>();
        actions.add(new CrossCellAction(BOT, 0, r.cells().get(0).id(), DiceCombination.WHITE_WHITE));
        actions.add(new DeclareLockIntentAction(BOT, 0));
        GameAction chosen = BotDecider.decide(state, BOT, actions);
        assertInstanceOf(DeclareLockIntentAction.class, chosen,
                "DeclareLockIntent must be chosen ahead of any cross");
    }

    // ── Rarity direction: rarer value wins at equal (zero) gap ────────────────

    @Test
    void bot_prefers_the_rarer_value_when_both_are_adjacent() {
        Row red    = stdRow(Color.RED);
        Row yellow = stdRow(Color.YELLOW);
        SheetProgress prog = emptyProg();
        crossFirst(prog, 0, red, 4);      // expose RED value 6 at gap 0  → loss -5
        crossFirst(prog, 1, yellow, 9);   // expose YELLOW value 11 at gap 0 → loss -20
        GameState state = buildState(Mode.PASSIVE, layout(red, yellow), prog);

        // Worse candidate listed first to also kill "removed best-update conditional".
        List<GameAction> actions = List.of(ww(0, red, 4), ww(1, yellow, 9));
        assertEquals(yellow.cells().get(9).id(), chosenId(BotDecider.decide(state, BOT, actions, BotProfile.DEFAULT)),
                "Rarer value 11 (loss -20) must beat common value 6 (loss -5)");
    }

    // ── Gap math: a rare-but-gapped cell wins, and inflating its gap flips it ─

    @Test
    void gapped_rare_cell_wins_but_only_because_gap_is_small() {
        Row red    = stdRow(Color.RED);      // will host a gapped rare 12
        Row yellow = stdRow(Color.YELLOW);   // adjacent common 7
        SheetProgress prog = emptyProg();
        crossFirst(prog, 0, red, 9);         // rightmost = pos8; RED 12 at pos10 → gap 1 → loss 3.5-25 = -21.5
        crossFirst(prog, 1, yellow, 5);      // YELLOW 7 at pos5 → gap 0 → loss 0
        GameState state = buildState(Mode.ACTIVE_SINGLE, layout(red, yellow), prog);

        List<GameAction> actions = List.of(ww(1, yellow, 5), ww(0, red, 10));
        assertEquals(red.cells().get(10).id(), chosenId(BotDecider.decide(state, BOT, actions, BotProfile.DEFAULT)),
                "Rare 12 at gap 1 (loss -21.5) must beat adjacent 7 (loss 0)");
    }

    // ── diceCenter: STANDARD (center 7) vs LONGO (center 9) flips the choice ──

    @Test
    void standard_center_7_makes_value_11_rarer_than_value_5() {
        Row red    = stdRow(Color.RED);      // values 2..12 → center 7
        Row yellow = stdRow(Color.YELLOW);
        SheetProgress prog = emptyProg();
        crossFirst(prog, 0, red, 3);         // RED value 5 at gap 0 → |5-7|=2 → loss -10
        crossFirst(prog, 1, yellow, 9);      // YELLOW value 11 at gap 0 → |11-7|=4 → loss -20
        GameState state = buildState(Mode.ACTIVE_SINGLE, layout(red, yellow), prog);

        List<GameAction> actions = List.of(ww(0, red, 3), ww(1, yellow, 9));
        assertEquals(yellow.cells().get(9).id(), chosenId(BotDecider.decide(state, BOT, actions, BotProfile.DEFAULT)),
                "With center 7, value 11 (loss -20) beats value 5 (loss -10)");
    }

    @Test
    void longo_center_9_makes_value_5_rarer_than_value_11() {
        Row red    = longoRow(Color.RED);    // values 2..16 → center 9
        Row yellow = longoRow(Color.YELLOW);
        SheetProgress prog = emptyProg();
        crossFirst(prog, 0, red, 3);         // RED value 5 at gap 0 → |5-9|=4 → loss -20
        crossFirst(prog, 1, yellow, 9);      // YELLOW value 11 at gap 0 → |11-9|=2 → loss -10
        GameState state = buildState(Mode.ACTIVE_SINGLE, layout(red, yellow), prog);

        List<GameAction> actions = List.of(ww(0, red, 3), ww(1, yellow, 9));
        assertEquals(red.cells().get(3).id(), chosenId(BotDecider.decide(state, BOT, actions, BotProfile.DEFAULT)),
                "With center 9, value 5 (loss -20) beats value 11 (loss -10)");
    }

    // ── parseDisplayValue: a non-numeric cell falls back to the dice centre ───

    @Test
    void non_numeric_cell_is_valued_at_the_dice_centre_not_zero() {
        // Numeric value-9 cell (loss -10) must beat a non-numeric cell whose fallback value is the
        // centre 7 (rarity 0 → loss 0). If the fallback were 0, the non-numeric cell would look
        // rare (|0-7|=7) and wrongly win.
        Row red = stdRow(Color.RED);         // numeric 2..12 → center 7
        Row weird = new Row();
        Cell star = new Cell(0);
        star.setColor(Color.YELLOW);
        star.setDisplayValue("★");      // non-numeric
        star.setTags(List.of());
        weird.addCell(star);

        SheetProgress prog = emptyProg();
        crossFirst(prog, 0, red, 7);         // RED value 9 at pos7, gap 0 → loss -10
        GameState state = buildState(Mode.ACTIVE_SINGLE, layout(red, weird), prog);

        List<GameAction> actions = List.of(ww(1, weird, 0), ww(0, red, 7));
        assertEquals(red.cells().get(7).id(), chosenId(BotDecider.decide(state, BOT, actions, BotProfile.DEFAULT)),
                "Numeric 9 (loss -10) must beat a non-numeric cell valued at the centre (loss 0)");
    }

    // ── nearLock: the lock bonus can tip a decision toward a near-lock row ────

    @Test
    void near_lock_bonus_tips_choice_toward_the_locking_row() {
        Row red    = rowWithLock(Color.RED, 6);   // gets to near-lock after 4 crosses
        Row yellow = stdRow(Color.YELLOW);         // lockless comparison row
        SheetProgress prog = emptyProg();
        crossFirst(prog, 0, red, 4);               // near-lock TRUE (missing 1 + needed 2 = 3 ≤ 3)
        crossFirst(prog, 1, yellow, 5);            // YELLOW 7 at gap 0 → loss 0
        GameState state = buildState(Mode.ACTIVE_SINGLE, layout(red, yellow), prog);

        // RED value 7 at pos5, gap 1 → 3.5 - lockBonus 5 = -1.5, beats adjacent 7 (loss 0).
        List<GameAction> actions = List.of(ww(1, yellow, 5), ww(0, red, 5));
        assertEquals(red.cells().get(5).id(), chosenId(BotDecider.decide(state, BOT, actions, BotProfile.DEFAULT)),
                "Near-lock gapped 7 (loss -1.5) must beat adjacent 7 (loss 0)");
    }

    @Test
    void row_far_from_a_lock_gets_no_lock_bonus() {
        // RED has a lock but is far from it (minCrosses 11, only 4 crosses) → nearLock must be FALSE,
        // so its gapped 7 (loss +3.5) must LOSE to YELLOW's adjacent 7 (loss 0). If the row were
        // wrongly treated as near-lock, RED would score -1.5 and win.
        Row red    = rowWithLock(Color.RED, 11);
        Row yellow = stdRow(Color.YELLOW);
        SheetProgress prog = emptyProg();
        crossFirst(prog, 0, red, 4);      // rightmost pos3; RED 7 at pos5 → gap 1 → loss +3.5 (no bonus)
        crossFirst(prog, 1, yellow, 5);   // YELLOW 7 at pos5 → gap 0 → loss 0
        GameState state = buildState(Mode.ACTIVE_SINGLE, layout(red, yellow), prog);

        List<GameAction> actions = List.of(ww(0, red, 5), ww(1, yellow, 5));
        assertEquals(yellow.cells().get(5).id(), chosenId(BotDecider.decide(state, BOT, actions, BotProfile.DEFAULT)),
                "A row far from its lock must not receive the lock bonus");
    }

    // ── Passive threshold: pass rather than make a positive-loss cross ────────

    @Test
    void passive_bot_passes_rather_than_making_a_positive_loss_cross() {
        Row red = stdRow(Color.RED);
        SheetProgress prog = emptyProg();
        crossFirst(prog, 0, red, 4);      // RED 7 at pos5 → gap 1 → loss 3.5 > passiveThreshold 0
        GameState state = buildState(Mode.PASSIVE, layout(red), prog);

        List<GameAction> actions = List.of(ww(0, red, 5), new EndTurnAction(BOT));
        assertInstanceOf(EndTurnAction.class, BotDecider.decide(state, BOT, actions, BotProfile.DEFAULT),
                "Passive bot must pass (EndTurn) when its best move has positive loss");
    }

    @Test
    void passive_bot_crosses_a_beneficial_cell_instead_of_passing() {
        Row red = stdRow(Color.RED);
        SheetProgress prog = emptyProg();
        // RED value 2 at pos0, gap 0 → loss -25 ≤ passiveThreshold 0 → must cross, not pass.
        GameState state = buildState(Mode.PASSIVE, layout(red), prog);

        List<GameAction> actions = List.of(ww(0, red, 0), new EndTurnAction(BOT));
        assertEquals(red.cells().get(0).id(), chosenId(BotDecider.decide(state, BOT, actions, BotProfile.DEFAULT)),
                "Passive bot must cross a beneficial cell rather than passing");
    }

    // ── Active punishment logic ──────────────────────────────────────────────

    @Test
    void active_bot_keeps_a_within_threshold_move_rather_than_punishing() {
        Row red = stdRow(Color.RED);
        SheetProgress prog = emptyProg();
        crossFirst(prog, 0, red, 4);      // RED 7 at pos5 → gap 1 → loss 3.5 ≤ punishmentLoss 15
        GameState state = buildState(Mode.ACTIVE_SINGLE, layout(red), prog);

        List<GameAction> actions = List.of(ww(0, red, 5), new TakePunishmentAction(BOT));
        assertEquals(red.cells().get(5).id(), chosenId(BotDecider.decide(state, BOT, actions, BotProfile.DEFAULT)),
                "Active bot must keep a move whose loss is within the punishment threshold");
    }

    @Test
    void active_bot_takes_a_punishment_when_every_move_is_too_lossy() {
        Row red = stdRow(Color.RED);
        SheetProgress prog = emptyProg();
        crossFirst(prog, 0, red, 2);      // rightmost pos1; RED 7 at pos5 → gap 3 → loss 18.2 > 15
        GameState state = buildState(Mode.ACTIVE_SINGLE, layout(red), prog);

        List<GameAction> actions = List.of(ww(0, red, 5), new TakePunishmentAction(BOT));
        assertInstanceOf(TakePunishmentAction.class, BotDecider.decide(state, BOT, actions, BotProfile.DEFAULT),
                "Active bot must take a punishment when its best move exceeds the punishment threshold");
    }

    @Test
    void active_bot_stops_punishing_once_the_max_is_reached() {
        Row red = stdRow(Color.RED);
        SheetProgress prog = new SheetProgress(new HashMap<>(), BotProfile.DEFAULT.maxPunishments());
        crossFirst(prog, 0, red, 2);      // RED 7 at pos5 → gap 3 → loss 18.2 > 15
        GameState state = buildState(Mode.ACTIVE_SINGLE, layout(red), prog);

        List<GameAction> actions = List.of(ww(0, red, 5), new TakePunishmentAction(BOT));
        assertEquals(red.cells().get(5).id(), chosenId(BotDecider.decide(state, BOT, actions, BotProfile.DEFAULT)),
                "At max punishments the bot must make the lossy cross instead of punishing again");
    }

    // ── Tag adjustments (all valued via the loss function) ────────────────────

    @Test
    void bonus_points_tag_makes_a_cell_more_attractive() {
        assertTagPreferred(new CellTag.BonusPoints(3), 7, 7,
                "A cell awarding bonus points must be preferred over an identical plain cell");
    }

    @Test
    void extra_bucket_tag_makes_a_cell_more_attractive() {
        assertTagPreferred(new CellTag.ExtraBucket(), 7, 7,
                "An ExtraBucket cell must be preferred over an identical plain cell");
    }

    @Test
    void lucky_number_tag_makes_a_cell_more_attractive() {
        assertTagPreferred(new CellTag.LuckyNumber(4), 7, 7,
                "A LuckyNumber cell must be preferred over an identical plain cell");
    }

    @Test
    void double_cross_tag_amplifies_a_rare_cell() {
        // value 12 → base loss -25; DoubleCross subtracts another |12-7|*5 = 25 → -50, beating plain -25.
        assertTagPreferred(new CellTag.DoubleCross(), 12, 12,
                "A DoubleCross rare cell must be preferred over an identical plain rare cell");
    }

    @Test
    void secondary_color_tag_amplifies_a_rare_cell() {
        assertTagPreferred(new CellTag.SecondaryColor(Color.BLUE), 12, 12,
                "A SecondaryColor rare cell must be preferred over an identical plain rare cell");
    }

    @Test
    void x_change_tag_makes_a_cell_less_attractive() {
        // XChange adds a positive penalty (rarityBonus - bestMatch)*0.5 > 0, so the plain cell must win.
        Row red    = stdRow(Color.RED);      // plain value 7
        Row yellow = stdRow(Color.YELLOW);   // XChange value 7
        SheetProgress prog = emptyProg();
        crossFirst(prog, 0, red, 5);         // plain 7 at gap 0 → loss 0
        Cell tagged = yellow.cells().get(5);
        tagged.setTags(List.of(new CellTag.XChange(6, 8)));   // bestMatch 1 → +(5-1)*0.5 = +2
        crossFirst(prog, 1, yellow, 5);
        GameState state = buildState(Mode.PASSIVE, layout(red, yellow), prog);

        List<GameAction> actions = List.of(ww(1, yellow, 5), ww(0, red, 5));
        assertEquals(red.cells().get(5).id(), chosenId(BotDecider.decide(state, BOT, actions, BotProfile.DEFAULT)),
                "An XChange cell (loss +2) must lose to a plain adjacent cell (loss 0)");
    }

    @Test
    void auto_cross_tag_adds_the_targets_loss_to_the_cell() {
        // The AutoCross cell is a common 7 (base loss 0) whose target is a rare 12 elsewhere (loss -25).
        // Crossing it effectively also secures the -25, so it must beat a plain rare 11 (loss -20).
        Row red    = stdRow(Color.RED);      // hosts the plain rival cell (value 11)
        Row yellow = stdRow(Color.YELLOW);   // hosts the AutoCross value-7 cell
        Row target = new Row();              // hosts the auto-crossed rare 12
        Cell targetCell = new Cell(0);
        targetCell.setColor(Color.GREEN);
        targetCell.setDisplayValue("12");
        targetCell.setTags(List.of());
        target.addCell(targetCell);

        SheetProgress prog = emptyProg();
        crossFirst(prog, 0, red, 9);         // RED value 11 at pos9, gap 0 → loss -20
        Cell auto = yellow.cells().get(5);
        auto.setTags(List.of(new CellTag.AutoCross(targetCell.id())));   // adds target loss -25
        crossFirst(prog, 1, yellow, 5);      // YELLOW value 7 at pos5, gap 0 → base loss 0
        GameState state = buildState(Mode.PASSIVE, layout(red, yellow, target), prog);

        List<GameAction> actions = List.of(ww(0, red, 9), ww(1, yellow, 5));
        assertEquals(yellow.cells().get(5).id(), chosenId(BotDecider.decide(state, BOT, actions, BotProfile.DEFAULT)),
                "AutoCross 7 (0 + target -25 = -25) must beat plain 11 (loss -20)");
    }

    // ── Two-move lookahead (active player, neither die used) ──────────────────

    @Test
    void two_move_picks_the_first_cross_with_the_better_loss() {
        Row red    = stdRow(Color.RED);
        Row yellow = stdRow(Color.YELLOW);
        SheetProgress prog = emptyProg();
        crossFirst(prog, 1, yellow, 5);      // YELLOW 7 at gap 0 → firstLoss 0
        GameState state = buildState(Mode.ACTIVE_TWO, layout(red, yellow), prog);

        // RED value 2 at pos0 → firstLoss -25 (better). Worse candidate listed first.
        List<GameAction> actions = List.of(ww(1, yellow, 5), ww(0, red, 0));
        assertEquals(red.cells().get(0).id(), chosenId(BotDecider.decide(state, BOT, actions, BotProfile.DEFAULT)),
                "Two-move lookahead must pick the first cross with the lower loss (-25 over 0)");
    }

    @Test
    void two_move_second_move_bonus_decides_between_equal_first_moves() {
        // Two equal first moves (both value 5, firstLoss -10). Only the RED first can be followed by
        // the beneficial WHITE_COLOR value-6 second AT GAP 0 (same row, position after it); the YELLOW
        // first only reaches that same second at gap 1 (worse). So RED must win.
        Row red    = stdRow(Color.RED);
        Row yellow = stdRow(Color.YELLOW);
        SheetProgress prog = emptyProg();
        crossFirst(prog, 0, red, 3);         // rightmost pos2
        crossFirst(prog, 1, yellow, 3);      // rightmost pos2
        GameState state = buildState(Mode.ACTIVE_TWO, layout(red, yellow), prog);

        CrossCellAction firstRed    = ww(0, red, 3);    // value 5, loss -10
        CrossCellAction firstYellow = ww(1, yellow, 3); // value 5, loss -10
        CrossCellAction secondRed6  = wc(0, red, 4);    // value 6 WHITE_COLOR
        List<GameAction> actions = List.of(firstYellow, secondRed6, firstRed);
        assertEquals(red.cells().get(3).id(), chosenId(BotDecider.decide(state, BOT, actions, BotProfile.DEFAULT)),
                "The first move enabling the better same-row second (-15 total) must be chosen");
    }

    @Test
    void two_move_same_row_second_gap_is_computed_against_the_first_cross() {
        // RED first (value 8) lets the RED second (value 9) land at GAP 0 (total -15); the YELLOW first
        // (value 8) only reaches that same value-9 second at gap 1 (total -11.5). RED must win, which
        // depends on the post-first-cross virtual gap of the second being 0.
        Row red    = stdRow(Color.RED);
        Row yellow = stdRow(Color.YELLOW);
        SheetProgress prog = emptyProg();
        crossFirst(prog, 0, red, 6);         // rightmost pos5
        crossFirst(prog, 1, yellow, 6);      // rightmost pos5
        GameState state = buildState(Mode.ACTIVE_TWO, layout(red, yellow), prog);

        CrossCellAction firstRed    = ww(0, red, 6);    // value 8, loss -5
        CrossCellAction firstYellow = ww(1, yellow, 6); // value 8, loss -5
        CrossCellAction secondRed9  = wc(0, red, 7);    // value 9 WHITE_COLOR
        List<GameAction> actions = List.of(firstYellow, secondRed9, firstRed);
        assertEquals(red.cells().get(6).id(), chosenId(BotDecider.decide(state, BOT, actions, BotProfile.DEFAULT)),
                "Same-row second must be scored at gap 0 after the first cross, making RED win");
    }

    @Test
    void two_move_takes_a_punishment_when_the_best_first_is_too_lossy() {
        Row red = stdRow(Color.RED);
        SheetProgress prog = emptyProg();
        crossFirst(prog, 0, red, 2);         // RED 7 at pos5 → gap 3 → firstLoss 18.2 > 15
        GameState state = buildState(Mode.ACTIVE_TWO, layout(red), prog);

        List<GameAction> actions = List.of(ww(0, red, 5), new TakePunishmentAction(BOT));
        assertInstanceOf(TakePunishmentAction.class, BotDecider.decide(state, BOT, actions, BotProfile.DEFAULT),
                "Two-move lookahead must punish when the best first cross exceeds the threshold");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Test builders
    // ══════════════════════════════════════════════════════════════════════════

    /** Asserts a cell carrying {@code tag} (value {@code taggedValue}) is chosen over a plain cell
     *  of value {@code plainValue}. Both cells sit at gap 0 in their own row. */
    private void assertTagPreferred(CellTag tag, int taggedValue, int plainValue, String message) {
        Row plainRow  = stdRow(Color.RED);
        Row taggedRow = stdRow(Color.YELLOW);
        SheetProgress prog = emptyProg();
        int plainPos  = plainValue - 2;
        int taggedPos = taggedValue - 2;
        crossFirst(prog, 0, plainRow, plainPos);      // plain cell at gap 0
        Cell tagged = taggedRow.cells().get(taggedPos);
        tagged.setTags(List.of(tag));
        crossFirst(prog, 1, taggedRow, taggedPos);    // tagged cell at gap 0
        GameState state = buildState(Mode.PASSIVE, layout(plainRow, taggedRow), prog);

        List<GameAction> actions = List.of(ww(0, plainRow, plainPos), ww(1, taggedRow, taggedPos));
        assertEquals(taggedRow.cells().get(taggedPos).id(),
                chosenId(BotDecider.decide(state, BOT, actions, BotProfile.DEFAULT)), message);
    }

    /** Ascending numeric row with values lo..hi at positions 0..(hi-lo). */
    private static Row ascRow(Color color, int lo, int hi) {
        Row row = new Row();
        for (int v = lo; v <= hi; v++) {
            Cell c = new Cell(v - lo);
            c.setColor(color);
            c.setDisplayValue(String.valueOf(v));
            c.setTags(List.of());
            row.addCell(c);
        }
        return row;
    }

    /** Standard row: values 2..12 → dice centre 7. */
    private static Row stdRow(Color color) {
        return ascRow(color, 2, 12);
    }

    /** Longo row: values 2..16 → dice centre 9. */
    private static Row longoRow(Color color) {
        return ascRow(color, 2, 16);
    }

    /** Standard row carrying a lock whose single closing cell is the last cell. */
    private static Row rowWithLock(Color color, int minCrosses) {
        Row row = stdRow(color);
        Cell last = row.cells().get(row.cells().size() - 1);
        row.addLock(new LockCell(UUID.randomUUID().toString(), color, minCrosses, List.of(last.id())));
        return row;
    }

    private static SheetLayout layout(Row... rows) {
        return new SheetLayout(List.of(rows));
    }

    private static SheetProgress emptyProg() {
        return new SheetProgress(new HashMap<>(), 0);
    }

    /** Cross the first {@code count} positions (0..count-1) contiguously — no gaps introduced. */
    private static void crossFirst(SheetProgress prog, int rowIndex, Row row, int count) {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < count; i++) ids.add(row.cells().get(i).id());
        prog.updateRowState(rowIndex, new RowState(ids, false));
    }

    private static CrossCellAction ww(int rowIndex, Row row, int pos) {
        return new CrossCellAction(BOT, rowIndex, row.cells().get(pos).id(), DiceCombination.WHITE_WHITE);
    }

    private static CrossCellAction wc(int rowIndex, Row row, int pos) {
        return new CrossCellAction(BOT, rowIndex, row.cells().get(pos).id(), DiceCombination.WHITE_COLOR);
    }

    private static String chosenId(GameAction action) {
        return assertInstanceOf(CrossCellAction.class, action).cellId();
    }

    private static GameState buildState(Mode mode, SheetLayout layout, SheetProgress prog) {
        Map<UUID, SheetLayout> layouts = new HashMap<>();
        layouts.put(BOT, layout);
        Map<UUID, SheetProgress> progress = new HashMap<>();
        progress.put(BOT, prog);
        BoardState board = new BoardState(progress, new ArrayList<>(), new HashMap<>());

        TurnState ts = new TurnState();
        ActiveTurnState ats = new ActiveTurnState();
        UUID active;
        switch (mode) {
            case PASSIVE -> {
                active = RIVAL;
                ts.setPhase(TurnPhase.PASSIVE_MOVE);
            }
            case ACTIVE_SINGLE -> {
                active = BOT;
                ats.setColorDieUsed();                 // one die used → single-move path
                ts.setPhase(TurnPhase.ACTIVE_MOVE);
            }
            case ACTIVE_TWO -> {
                active = BOT;                           // neither die used → two-move lookahead
                ts.setPhase(TurnPhase.ACTIVE_MOVE);
            }
            default -> throw new IllegalStateException();
        }
        ts.setActivePlayerId(active);
        ts.setActiveTurnState(ats);
        return new GameState(CardMode.SAME_CARDS, List.of(BOT, RIVAL), null, layouts, board, ts);
    }
}
