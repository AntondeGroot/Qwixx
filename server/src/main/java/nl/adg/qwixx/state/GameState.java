package nl.adg.qwixx.state;

import nl.adg.qwixx.game.VariantData;

import java.util.List;
import java.util.Map;
import java.util.UUID;

// Top-level envelope. SheetLayout lives here as it is static; BoardState holds only what changes.
public class GameState {
    CardMode               cardMode;
    List<UUID>             players;        // ordered; defines turn order
    VariantData            variantData;    // opaque, variant-specific data
    Map<UUID, SheetLayout> sheetLayouts;   // static layout per player
    BoardState             boardState;
    TurnState              turnState;
    boolean                gameOver;
    long                   version;        // increments on every applyAction

    public GameState(CardMode cardMode, List<UUID> players, VariantData variantData,
                     Map<UUID, SheetLayout> sheetLayouts, BoardState boardState,
                     TurnState turnState) {
        this.cardMode     = cardMode;
        this.players      = players;
        this.variantData  = variantData;
        this.sheetLayouts = sheetLayouts;
        this.boardState   = boardState;
        this.turnState    = turnState;
        this.gameOver     = false;
        this.version      = 0;
    }

    public CardMode cardMode()                   { return cardMode; }
    public List<UUID> players()                  { return players; }
    public VariantData variantData()             { return variantData; }
    public Map<UUID, SheetLayout> sheetLayouts() { return sheetLayouts; }
    public BoardState boardState()               { return boardState; }
    public TurnState turnState()                 { return turnState; }
    public boolean gameOver()                    { return gameOver; }
    public long version()                        { return version; }

    public void setTurnState(TurnState turnState)   { this.turnState = turnState; }
    public void setGameOver(boolean gameOver)       { this.gameOver = gameOver; }
    public void setVariantData(VariantData vd)      { this.variantData = vd; }
    public void incrementVersion()                  { this.version++; }
}