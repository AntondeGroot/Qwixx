package nl.adg.qwixx.state;

import nl.adg.qwixx.data.RollResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// Everything scoped to the turn currently in progress. Rebuilt at the start of each turn.
public class TurnState {
    UUID activePlayerId;
    TurnPhase phase;
    List<UUID> passivePlayerQueue;
    RollResult currentRoll;
    ActiveTurnState activeTurnState;
    Integer pendingLockRowIndex;     // null outside LOCK_PENDING
    Set<UUID> lockAcknowledged;
    Map<UUID, SheetProgress> moveStartProgress;
    // cells crossed by each player's last CrossCellAction: playerId → rowIndex → cellIds
    Map<UUID, Map<Integer, Set<String>>> undoBuffer;

    public TurnState() {
        this.passivePlayerQueue  = new ArrayList<>();
        this.lockAcknowledged    = new HashSet<>();
        this.moveStartProgress   = new HashMap<>();
        this.undoBuffer          = new HashMap<>();
    }

    public UUID activePlayerId()                          { return activePlayerId; }
    public TurnPhase phase()                              { return phase; }
    public List<UUID> passivePlayerQueue()                { return passivePlayerQueue; }
    public RollResult currentRoll()                       { return currentRoll; }
    public ActiveTurnState activeTurnState()              { return activeTurnState; }
    public Integer pendingLockRowIndex()                  { return pendingLockRowIndex; }
    public Set<UUID> lockAcknowledged()                   { return lockAcknowledged; }
    public Map<UUID, SheetProgress> moveStartProgress()   { return moveStartProgress; }
    public Map<UUID, Map<Integer, Set<String>>> undoBuffer() { return undoBuffer; }

    public void setActivePlayerId(UUID id)                          { this.activePlayerId = id; }
    public void setPhase(TurnPhase phase)                           { this.phase = phase; }
    public void setPassivePlayerQueue(List<UUID> queue)             { this.passivePlayerQueue = queue; }
    public void setCurrentRoll(RollResult roll)                     { this.currentRoll = roll; }
    public void setActiveTurnState(ActiveTurnState ats)             { this.activeTurnState = ats; }
    public void setPendingLockRowIndex(Integer idx)                  { this.pendingLockRowIndex = idx; }
    public void setLockAcknowledged(Set<UUID> acknowledged)         { this.lockAcknowledged = acknowledged; }
    public void setMoveStartProgress(Map<UUID, SheetProgress> snap) { this.moveStartProgress = snap; }
    public void setUndoBuffer(Map<UUID, Map<Integer, Set<String>>> buf) { this.undoBuffer = buf; }
}