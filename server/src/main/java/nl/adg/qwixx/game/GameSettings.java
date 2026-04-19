package nl.adg.qwixx.game;

import nl.adg.qwixx.state.CardMode;

public class GameSettings {
    private final BaseVariant base;
    private final boolean randomOrder;
    private final boolean connectedCells;
    private final boolean extraRow;
    private final boolean mixedColors;
    private final CardMode cardMode;
    private final GameMode gameMode;

    private GameSettings(Builder builder) {
        this.base = builder.base;
        this.randomOrder = builder.randomOrder;
        this.connectedCells = builder.connectedCells;
        this.extraRow = builder.extraRow;
        this.mixedColors = builder.mixedColors;
        this.cardMode = builder.cardMode;
        this.gameMode = builder.gameMode;
    }

    public BaseVariant base()       { return base; }
    public boolean randomOrder()    { return randomOrder; }
    public boolean connectedCells() { return connectedCells; }
    public boolean extraRow()       { return extraRow; }
    public boolean mixedColors()    { return mixedColors; }
    public CardMode cardMode()      { return cardMode; }
    public GameMode gameMode()      { return gameMode; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private BaseVariant base = BaseVariant.STANDARD;
        private boolean randomOrder = false;
        private boolean connectedCells = false;
        private boolean extraRow = false;
        private boolean mixedColors = false;
        private CardMode cardMode = CardMode.DETERMINISTIC;
        private GameMode gameMode = GameMode.ONLINE;

        public Builder base(BaseVariant base)       { this.base = base; return this; }
        public Builder randomOrder(boolean v)       { this.randomOrder = v; return this; }
        public Builder connectedCells(boolean v)    { this.connectedCells = v; return this; }
        public Builder extraRow(boolean v)          { this.extraRow = v; return this; }
        public Builder mixedColors(boolean v)       { this.mixedColors = v; return this; }
        public Builder cardMode(CardMode cardMode)  { this.cardMode = cardMode; return this; }
        public Builder gameMode(GameMode gameMode)  { this.gameMode = gameMode; return this; }
        public GameSettings build()                 { return new GameSettings(this); }
    }
}