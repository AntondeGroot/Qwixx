package nl.adg.qwixx.rules;

import nl.adg.qwixx.state.GameState;
import nl.adg.qwixx.state.TurnState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

class TurnHelper {

    private TurnHelper() {}

    // ── Passive slot queries ──────────────────────────────────────────────────

    /** True if playerId is in the passive queue AND is not the active player. */
    static boolean isPassiveInQueue(TurnState turn, UUID playerId) {
        return !playerId.equals(turn.activePlayerId())
                && turn.passivePlayerQueue().contains(playerId);
    }

    /** True if the player crossed a cell this turn (cross is pending in the undo buffer). */
    static boolean hasPendingCross(TurnState turn, UUID playerId) {
        return turn.undoBuffer().containsKey(playerId);
    }

    /** True if the player has used their passive white+white slot this turn (cross or lock intent). */
    static boolean hasAlreadyActed(TurnState turn, UUID playerId) {
        return hasPendingCross(turn, playerId) || turn.passivesActed().contains(playerId);
    }

    // ── Lock acknowledgement queries ──────────────────────────────────────────

    /**
     * True when every player in the passive queue has acknowledged the lock intent AND completed
     * their passive move opportunity (or auto-passed). Players leave the queue only after both
     * steps — this is the trigger condition for applying the lock.
     */
    static boolean allNonActiveAcknowledged(GameState state) {
        return state.turnState().passivePlayerQueue().isEmpty();
    }

    // ── Passive queue construction ────────────────────────────────────────────

    /**
     * All players except excludedPlayer who have not yet used their white+white slot
     * this turn (i.e. not in passivesActed).
     */
    static List<UUID> unactedPassives(GameState state, UUID excludedPlayer) {
        TurnState turn = state.turnState();
        List<UUID> remaining = new ArrayList<>(state.players());
        remaining.remove(excludedPlayer);
        remaining.removeIf(pid -> turn.passivesActed().contains(pid));
        return remaining;
    }

    // ── Turn state mutations ──────────────────────────────────────────────────

    /** Clears all LOCK_PENDING bookkeeping, readying the turn for a new phase. */
    static void clearPendingLock(TurnState turn) {
        turn.setPendingLockRowIndex(null);
        turn.setPendingLockDeclarerId(null);
        turn.setLockAcknowledged(new HashSet<>());
    }
}