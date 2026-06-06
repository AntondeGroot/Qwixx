package nl.adg.qwixx.state;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import nl.adg.qwixx.data.RollResult;

// Everything scoped to the turn currently in progress. Rebuilt at the start of each turn.
public class TurnState {
    UUID activePlayerId;
    TurnPhase phase;
    List<UUID> passivePlayerQueue;
    RollResult currentRoll;
    ActiveTurnState activeTurnState;
    Set<UUID> passivesActed;         // passives who used their white+white slot this turn
    Set<UUID> luckyCrossUsed;        // all players who used their Lucky Cross bonus this turn
    Map<UUID, SheetProgress> moveStartProgress;
    // cells crossed by each player's last CrossCellAction: playerId → rowIndex → cellIds
    Map<UUID, Map<Integer, Set<String>>> undoBuffer;
    // effective white+white value per player after crossing an x-change cell this turn
    Map<UUID, Integer> xChangeEffectiveWW;

    public TurnState() {
        this.passivePlayerQueue  = new ArrayList<>();
        this.passivesActed       = new HashSet<>();
        this.luckyCrossUsed      = new HashSet<>();
        this.moveStartProgress   = new HashMap<>();
        this.undoBuffer          = new HashMap<>();
        this.xChangeEffectiveWW  = new HashMap<>();
    }

    public UUID activePlayerId()                          { return activePlayerId; }
    public TurnPhase phase()                              { return phase; }
    public List<UUID> passivePlayerQueue()                { return passivePlayerQueue; }
    public RollResult currentRoll()                       { return currentRoll; }
    public ActiveTurnState activeTurnState()              { return activeTurnState; }
    public Set<UUID> passivesActed()                      { return passivesActed; }
    public Set<UUID> luckyCrossUsed()                     { return luckyCrossUsed; }
    public Map<UUID, SheetProgress> moveStartProgress()   { return moveStartProgress; }
    public Map<UUID, Map<Integer, Set<String>>> undoBuffer() { return undoBuffer; }
    public Map<UUID, Integer> xChangeEffectiveWW()        { return xChangeEffectiveWW; }

    public void setActivePlayerId(UUID id)                             { this.activePlayerId = id; }
    public void setPhase(TurnPhase phase)                              { this.phase = phase; }
    public void setPassivePlayerQueue(List<UUID> queue)                { this.passivePlayerQueue = queue; }
    public void setCurrentRoll(RollResult roll)                        { this.currentRoll = roll; }
    public void setActiveTurnState(ActiveTurnState ats)                { this.activeTurnState = ats; }
    public void setPassivesActed(Set<UUID> s)                          { this.passivesActed = s; }
    public void setLuckyCrossUsed(Set<UUID> s)                         { this.luckyCrossUsed = s; }
    public void setMoveStartProgress(Map<UUID, SheetProgress> snap)    { this.moveStartProgress = snap; }
    public void setUndoBuffer(Map<UUID, Map<Integer, Set<String>>> buf) { this.undoBuffer = buf; }
    public void setXChangeEffectiveWW(Map<UUID, Integer> map)          { this.xChangeEffectiveWW = map; }
}
