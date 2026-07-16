package nl.adg.qwixx.game;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import nl.adg.qwixx.bot.BotStrategy;
import nl.adg.qwixx.state.CardMode;

public class QwixxGameOptions {

    private static final Logger log = Logger.getLogger(QwixxGameOptions.class.getName());
    private static final String BASE = "base";
    private static final String BOT_COUNT = "botCount";
    private static final String BOT_STRATEGY = "botStrategy";
    private static final String CARD_MODE = "cardMode";
    private static final String CONNECTED_CELLS = "connectedCells";
    private static final String EXTRA_ROW = "extraRow";
    private static final String GAME_MODE = "gameMode";
    private static final String BIG_POINTS   = "bigPoints";
    private static final String RANDOM_ORDER = "randomOrder";
    private static final String X_CHANGE     = "xChange";
    private static final String LUCKY_NUMBER = "luckyNumber";
    private static final String LUCKY_CROSS  = "luckyCross";
    private static final String DOUBLE_A     = "doubleA";
    private static final String DOUBLE_B     = "doubleB";
    private static final String BONUS_A      = "bonusA";
    private static final String SEE_OTHER_CARDS = "seeOtherCards";

    private QwixxGameOptions() {}

    public static List<GameOption> all() {
        return List.of(
            GameOption.enumOption(BASE, "gameOption.base", "gameOption.baseDescription",
                "STANDARD", List.of("STANDARD", "LONGO")),
            GameOption.enumOption(GAME_MODE, "gameOption.gameMode", "gameOption.gameModeDescription",
                "ONLINE", List.of("ONLINE", "OFFLINE")),
            GameOption.enumOption(CARD_MODE, "gameOption.cardMode", "gameOption.cardModeDescription",
                "SAME_CARDS", List.of("SAME_CARDS", "DIFFERENT_CARDS")),
            GameOption.adminBoolOption(BIG_POINTS, "gameOption.bigPoints", "gameOption.bigPointsDescription")
                    .withIncompatibleWith(List.of(RANDOM_ORDER, LUCKY_CROSS, DOUBLE_A, DOUBLE_B, BONUS_A)),
            GameOption.boolOption(RANDOM_ORDER, "gameOption.randomOrder", "gameOption.randomOrderDescription")
                    .withIncompatibleWith(List.of(BIG_POINTS, BONUS_A)),
            GameOption.boolOption(EXTRA_ROW, "gameOption.extraRow", "gameOption.extraRowDescription")
                    .withIncompatibleWith(List.of(BONUS_A)),
            GameOption.boolOption(CONNECTED_CELLS, "gameOption.connectedCells", "gameOption.connectedCellsDescription")
                    .withIncompatibleWith(List.of(DOUBLE_A, DOUBLE_B, BONUS_A)),
            GameOption.boolOption(SEE_OTHER_CARDS, "gameOption.seeOtherCards", "gameOption.seeOtherCardsDescription", true),
            GameOption.adminBoolOption(X_CHANGE, "gameOption.xchange", "gameOption.xchangeDescription"),
            GameOption.adminBoolOption(LUCKY_NUMBER, "gameOption.luckyNumber", "gameOption.luckyNumberDescription"),
            GameOption.adminBoolOption(LUCKY_CROSS,  "gameOption.luckyCross",  "gameOption.luckyCrossDescription")
                    .withIncompatibleWith(List.of(BIG_POINTS, DOUBLE_A, DOUBLE_B, BONUS_A)),
            GameOption.adminBoolOption(DOUBLE_A, "gameOption.doubleA", "gameOption.doubleADescription")
                    .withIncompatibleWith(List.of(DOUBLE_B, BIG_POINTS, LUCKY_CROSS, CONNECTED_CELLS, BONUS_A)),
            GameOption.adminBoolOption(DOUBLE_B, "gameOption.doubleB", "gameOption.doubleBDescription")
                    .withIncompatibleWith(List.of(DOUBLE_A, BIG_POINTS, LUCKY_CROSS, CONNECTED_CELLS, BONUS_A)),
            GameOption.adminBoolOption(BONUS_A, "gameOption.bonusA", "gameOption.bonusADescription")
                    .withIncompatibleWith(List.of(BIG_POINTS, DOUBLE_A, DOUBLE_B, CONNECTED_CELLS, LUCKY_CROSS, EXTRA_ROW, RANDOM_ORDER)),
            GameOption.intOption(BOT_COUNT, "gameOption.botCount", "gameOption.botCountDescription",
                "0", 0, 3),
            GameOption.enumOption(BOT_STRATEGY, "gameOption.botStrategy", "gameOption.botStrategyDescription",
                "BALANCED", List.of("UNTRAINED", "MOST_POINTS", "MOST_WINS", "BALANCED"))
        );
    }

    /** Serializes the current settings back to the same key/value format used by the API. */
    public static Map<String, Object> toMap(GameSettings s) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(BASE,            s.base().name());
        map.put(GAME_MODE,       s.gameMode().name());
        map.put(CARD_MODE,       s.cardMode().name());
        map.put(BIG_POINTS,      s.bigPoints());
        map.put(RANDOM_ORDER,    s.randomOrder());
        map.put(EXTRA_ROW,       s.extraRow());
        map.put(CONNECTED_CELLS, s.connectedCells());
        map.put(SEE_OTHER_CARDS, s.seeOtherCards());
        map.put(X_CHANGE,        s.xChange());
        map.put(LUCKY_NUMBER,    s.luckyNumber());
        map.put(LUCKY_CROSS,     s.luckyCross());
        map.put(DOUBLE_A,        s.doubleA());
        map.put(DOUBLE_B,        s.doubleB());
        map.put(BONUS_A,         s.bonusA());
        map.put(BOT_COUNT,       s.botCount());
        map.put(BOT_STRATEGY,    s.botStrategy() != null ? s.botStrategy().name()
                                                           : BotStrategy.BALANCED.name());
        return map;
    }

    public static void apply(GameSettings.Builder builder, Map<String, Object> options) {
        if (options == null) return;
        for (var entry : options.entrySet()) {
            switch (entry.getKey()) {
                case BASE            -> builder.base(BaseVariant.valueOf(str(entry.getValue())));
                case GAME_MODE       -> builder.gameMode(GameMode.valueOf(str(entry.getValue())));
                case CARD_MODE       -> builder.cardMode(CardMode.valueOf(str(entry.getValue())));
                case BIG_POINTS      -> builder.bigPoints(bool(entry.getValue()));
                case RANDOM_ORDER    -> builder.randomOrder(bool(entry.getValue()));
                case EXTRA_ROW       -> builder.extraRow(bool(entry.getValue()));
                case CONNECTED_CELLS -> builder.connectedCells(bool(entry.getValue()));
                case SEE_OTHER_CARDS -> builder.seeOtherCards(bool(entry.getValue()));
                case X_CHANGE        -> builder.xChange(bool(entry.getValue()));
                case LUCKY_NUMBER    -> builder.luckyNumber(bool(entry.getValue()));
                case LUCKY_CROSS     -> builder.luckyCross(bool(entry.getValue()));
                case DOUBLE_A        -> builder.doubleA(bool(entry.getValue()));
                case DOUBLE_B        -> builder.doubleB(bool(entry.getValue()));
                case BONUS_A         -> builder.bonusA(bool(entry.getValue()));
                case BOT_COUNT       -> builder.botCount(integer(entry.getValue()));
                case BOT_STRATEGY    -> builder.botStrategy(BotStrategy.valueOf(str(entry.getValue())));
                default              -> log.warning("unknown game option '" + entry.getKey() + "', ignoring");
            }
        }
    }

    private static String str(Object value) {
        return value == null ? null : value.toString().trim().toUpperCase();
    }

    private static boolean bool(Object value) {
        if (value instanceof Boolean b) return b;
        return Boolean.parseBoolean(value == null ? "false" : value.toString().trim());
    }

    private static int integer(Object value) {
        if (value instanceof Number n) return n.intValue();
        return Integer.parseInt(value == null ? "0" : value.toString().trim());
    }
}
