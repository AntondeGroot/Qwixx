package nl.adg.qwixx.game;

import java.util.List;
import nl.adg.qwixx.bot.BotProfile;
import nl.adg.qwixx.bot.BotStrategy;
import nl.adg.qwixx.state.CardMode;

public class GameSettings {
    private final BaseVariant  base;
    private final boolean      randomOrder;
    private final boolean      connectedCells;
    private final boolean      extraRow;
    private final boolean      bigPoints;
    private final boolean      xChange;
    private final boolean      luckyNumber;
    private final boolean      luckyCross;
    private final boolean      mixedColors;
    private final boolean      doubleA;
    private final boolean      doubleB;
    private final boolean      seeOtherCards;
    private final CardMode     cardMode;
    private final GameMode     gameMode;
    private final int          botCount;
    /** Playing style applied to all bots (overridden per-seat by {@code botProfiles} when non-empty). */
    private final BotStrategy  botStrategy;
    /** Per-seat profile overrides for the simulator. Empty = use {@code botStrategy} for all bots. */
    private final List<BotProfile> botProfiles;

    private GameSettings(Builder builder) {
        this.base        = builder.base;
        this.randomOrder = builder.randomOrder;
        this.connectedCells = builder.connectedCells;
        this.extraRow    = builder.extraRow;
        this.bigPoints   = builder.bigPoints;
        this.xChange     = builder.xChange;
        this.luckyNumber = builder.luckyNumber;
        this.luckyCross  = builder.luckyCross;
        this.mixedColors = builder.mixedColors;
        this.doubleA     = builder.doubleA;
        this.doubleB     = builder.doubleB;
        this.seeOtherCards = builder.seeOtherCards;
        this.cardMode    = builder.cardMode;
        this.gameMode    = builder.gameMode;
        this.botCount    = builder.botCount;
        this.botStrategy = builder.botStrategy;
        this.botProfiles = List.copyOf(builder.botProfiles);
    }

    public BaseVariant     base()           { return base; }
    public boolean         randomOrder()    { return randomOrder; }
    public boolean         connectedCells() { return connectedCells; }
    public boolean         extraRow()       { return extraRow; }
    public boolean         bigPoints()      { return bigPoints; }
    public boolean         xChange()        { return xChange; }
    public boolean         luckyNumber()    { return luckyNumber; }
    public boolean         luckyCross()     { return luckyCross; }
    public boolean         mixedColors()    { return mixedColors; }
    public boolean         doubleA()        { return doubleA; }
    public boolean         doubleB()        { return doubleB; }
    public boolean         seeOtherCards()  { return seeOtherCards; }
    public CardMode        cardMode()       { return cardMode; }
    public GameMode        gameMode()       { return gameMode; }
    public int             botCount()       { return botCount; }
    public BotStrategy     botStrategy()   { return botStrategy; }
    public List<BotProfile> botProfiles()  { return botProfiles; }

    /** Profile for bot at seat {@code index}: per-seat override → strategy → DEFAULT. */
    public BotProfile profileForBot(int index) {
        if (index < botProfiles.size()) return botProfiles.get(index);
        return botStrategy != null ? botStrategy.profile() : BotProfile.DEFAULT;
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private BaseVariant    base           = BaseVariant.STANDARD;
        private boolean        randomOrder;
        private boolean        connectedCells;
        private boolean        extraRow;
        private boolean        bigPoints;
        private boolean        xChange;
        private boolean        luckyNumber;
        private boolean        luckyCross;
        private boolean        mixedColors;
        private boolean        doubleA;
        private boolean        doubleB;
        private boolean        seeOtherCards  = true;
        private CardMode       cardMode       = CardMode.DETERMINISTIC;
        private GameMode       gameMode       = GameMode.ONLINE;
        private int            botCount;
        private BotStrategy    botStrategy    = BotStrategy.BALANCED;
        private List<BotProfile> botProfiles  = List.of();

        public Builder base(BaseVariant base)              { this.base = base; return this; }
        public Builder randomOrder(boolean v)              { this.randomOrder = v; return this; }
        public Builder connectedCells(boolean v)           { this.connectedCells = v; return this; }
        public Builder extraRow(boolean v)                 { this.extraRow = v; return this; }
        public Builder bigPoints(boolean v)                { this.bigPoints = v; return this; }
        public Builder xChange(boolean v)                  { this.xChange = v; return this; }
        public Builder luckyNumber(boolean v)              { this.luckyNumber = v; return this; }
        public Builder luckyCross(boolean v)               { this.luckyCross = v; return this; }
        public Builder mixedColors(boolean v)              { this.mixedColors = v; return this; }
        public Builder doubleA(boolean v)                  { this.doubleA = v; return this; }
        public Builder doubleB(boolean v)                  { this.doubleB = v; return this; }
        public Builder seeOtherCards(boolean v)            { this.seeOtherCards = v; return this; }
        public Builder cardMode(CardMode cardMode)         { this.cardMode = cardMode; return this; }
        public Builder gameMode(GameMode gameMode)         { this.gameMode = gameMode; return this; }
        public Builder botCount(int v)                     { this.botCount = v; return this; }
        public Builder botStrategy(BotStrategy v)          { this.botStrategy = v; return this; }
        public Builder botProfiles(List<BotProfile> v)    { this.botProfiles = List.copyOf(v); return this; }
        public GameSettings build() {
            if (randomOrder && bigPoints) {
                throw new IllegalArgumentException(
                    "randomOrder and bigPoints cannot be combined: shuffling the colour rows " +
                    "breaks the positional guardrail that makes the bonus prerequisite meaningful");
            }
            if (luckyCross && bigPoints) {
                throw new IllegalArgumentException(
                    "luckyCross and bigPoints cannot be combined: lucky cross fields are " +
                    "interleaved in the coloured rows, which is incompatible with the bonus row layout");
            }
            if (doubleA && doubleB) {
                throw new IllegalArgumentException(
                    "doubleA and doubleB cannot be combined: they are alternative double-variant layouts");
            }
            if ((doubleA || doubleB) && (bigPoints || luckyCross || connectedCells)) {
                throw new IllegalArgumentException(
                    "the double variants cannot be combined with bigPoints, luckyCross or connectedCells: " +
                    "each restructures the coloured rows in an incompatible way");
            }
            return new GameSettings(this);
        }
    }
}
