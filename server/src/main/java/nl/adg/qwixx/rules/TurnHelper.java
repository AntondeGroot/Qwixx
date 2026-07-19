package nl.adg.qwixx.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import nl.adg.qwixx.state.GameState;
import nl.adg.qwixx.state.TurnState;

class TurnHelper {

    private TurnHelper() {}

    static boolean isPassiveInQueue(TurnState turn, UUID playerId) {
        // The active player can be re-queued for a final look at another player's row closure;
        // queue membership is the only gate needed (the active player is never in the queue
        // at roll-time since applyRoll removes them explicitly).
        return turn.passivePlayerQueue().contains(playerId);
    }

    static boolean hasPendingCross(TurnState turn, UUID playerId) {
        return turn.undoBuffer().containsKey(playerId);
    }

    static boolean hasAlreadyActed(TurnState turn, UUID playerId) {
        if (turn.passivesActed().contains(playerId)) return true;
        if (!hasPendingCross(turn, playerId)) return false;
        // An x-change-only pending cross doesn't consume the WW slot; the player still
        // needs to cross a regular cell with the new effectiveWW value.
        return !turn.xChangeEffectiveWW().containsKey(playerId);
    }

    static List<UUID> unactedPassives(GameState state, UUID excludedPlayer) {
        TurnState turn = Objects.requireNonNull(state.turnState());
        List<UUID> remaining = new ArrayList<>(state.players());
        remaining.remove(excludedPlayer);
        remaining.removeIf(pid -> turn.passivesActed().contains(pid));
        return remaining;
    }
}
