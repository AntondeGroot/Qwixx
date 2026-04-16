package nl.adg.qwixx.state;


import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import nl.adg.qwixx.data.RollResult;

//*
/*
  Everything scoped to the turn currently in progress. Rebuilt at the start of each turn.
 */
public class TurnState {
    UUID activePlayerId;               // next is players[(indexOf(activePlayerId) + 1) % players.size()]
    TurnPhase phase;
    List<UUID> passivePlayerQueue;     // derived from players minus activePlayerId at turn start;
    // shrinks as each passive player finishes their move
    RollResult currentRoll  ;          // null during ROLL phase
    ActiveTurnState             activeTurnState;       // null outside ACTIVE_MOVE
    Integer pendingLockRowId;         // null outside LOCK_PENDING; the row the active player intends to close
    Set<UUID> lockAcknowledged;       // players who have either undone or passed during LOCK_PENDING
    Map<UUID, SheetProgress> moveStartProgress;     // snapshot of each player's progress taken when their
    // move phase begins; ResetTurnAction restores only
    // that player's SheetProgress entry
}
