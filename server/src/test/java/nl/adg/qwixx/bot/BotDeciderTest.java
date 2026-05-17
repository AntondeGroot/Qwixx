package nl.adg.qwixx.bot;

import org.junit.jupiter.api.Test;

import static nl.adg.qwixx.bot.BotDecider.*;
import static org.junit.jupiter.api.Assertions.*;

class BotDeciderTest {

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
}