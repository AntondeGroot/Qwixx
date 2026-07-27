package nl.adg.qwixx.state;

import jakarta.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

// Top-level envelope. SheetLayout lives here as it is static; BoardState holds only what changes.
public class GameState {
    final CardMode               cardMode;
    final List<UUID>             players;        // ordered; defines turn order
    @Nullable VariantData  variantData;    // opaque, variant-specific data
    final Map<UUID, SheetLayout> sheetLayouts;   // static layout per player
    final BoardState             boardState;
    @Nullable TurnState    turnState;
    final List<ClosureNotification> closureNotifications; // pending row closure notifications (UI)
    final List<PunishmentNotification> punishmentNotifications; // "player hit max punishments → game ends" (UI)
    final Map<Integer, Set<UUID>> pendingClosures;   // rows queued to close at EVALUATE: rowIndex → declarerIds
    boolean                gameOver;
    long                   version;        // increments on every applyAction

    public GameState(CardMode cardMode, List<UUID> players, @Nullable VariantData variantData,
                     Map<UUID, SheetLayout> sheetLayouts, BoardState boardState,
                     @Nullable TurnState turnState) {
        this.cardMode         = cardMode;
        this.players          = players;
        this.variantData      = variantData;
        this.sheetLayouts     = sheetLayouts;
        this.boardState       = boardState;
        this.turnState        = turnState;
        this.closureNotifications = new ArrayList<>();
        this.punishmentNotifications = new ArrayList<>();
        this.pendingClosures  = new HashMap<>();
        this.gameOver         = false;
        this.version          = 0;
    }

    public CardMode cardMode()                        { return cardMode; }
    public List<UUID> players()                       { return players; }
    @Nullable public VariantData variantData()        { return variantData; }
    public Map<UUID, SheetLayout> sheetLayouts()      { return sheetLayouts; }
    public BoardState boardState()                    { return boardState; }
    @Nullable public TurnState turnState()            { return turnState; }
    public List<ClosureNotification> closureNotifications() { return closureNotifications; }
    public List<PunishmentNotification> punishmentNotifications() { return punishmentNotifications; }
    public Map<Integer, Set<UUID>> pendingClosures()  { return pendingClosures; }
    public boolean gameOver()                         { return gameOver; }
    public long version()                             { return version; }

    public Map<Integer, UUID> closedRows()           { return boardState.closedRows(); }
    public boolean isRowClosed(int rowIndex)         { return boardState.closedRows().containsKey(rowIndex); }

    /** The layout for a seated player. Every seated player always has one; the lookup never misses. */
    public SheetLayout sheetLayout(UUID playerId) {
        return Objects.requireNonNull(sheetLayouts.get(playerId), "no layout for player");
    }

    /** The progress for a seated player. Every seated player always has one; the lookup never misses. */
    public SheetProgress sheetProgress(UUID playerId) {
        return Objects.requireNonNull(boardState.sheetProgress().get(playerId), "no progress for player");
    }

    public void setTurnState(TurnState turnState)   { this.turnState = turnState; }
    public void setGameOver(boolean gameOver)       { this.gameOver = gameOver; }
    public void setVariantData(VariantData vd)      { this.variantData = vd; }
    public void incrementVersion()                  { this.version++; }
}
