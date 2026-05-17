package nl.adg.qwixx.game;

import nl.adg.qwixx.bot.BotStrategy;
import nl.adg.qwixx.state.CardMode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public class QwixxGameOptions {

    private static final Logger log = Logger.getLogger(QwixxGameOptions.class.getName());

    private QwixxGameOptions() {}

    public static List<GameOption> all() {
        return List.of(
            GameOption.enumOption("base", "gameOption.base", "gameOption.baseDescription",
                "STANDARD", List.of("STANDARD", "LONGO")),
            GameOption.enumOption("gameMode", "gameOption.gameMode", "gameOption.gameModeDescription",
                "ONLINE", List.of("ONLINE", "OFFLINE")),
            GameOption.enumOption("cardMode", "gameOption.cardMode", "gameOption.cardModeDescription",
                "DETERMINISTIC", List.of("DETERMINISTIC", "PROBABILISTIC")),
            GameOption.boolOption("randomOrder", "gameOption.randomOrder", "gameOption.randomOrderDescription"),
            GameOption.boolOption("extraRow", "gameOption.extraRow", "gameOption.extraRowDescription"),
            GameOption.boolOption("connectedCells", "gameOption.connectedCells", "gameOption.connectedCellsDescription"),
            GameOption.intOption("botCount", "gameOption.botCount", "gameOption.botCountDescription",
                "0", 0, 3),
            GameOption.enumOption("botStrategy", "gameOption.botStrategy", "gameOption.botStrategyDescription",
                "BALANCED", List.of("UNTRAINED", "MOST_POINTS", "MOST_WINS", "BALANCED"))
        );
    }

    /** Serialises the current settings back to the same key/value format used by the API. */
    public static Map<String, Object> toMap(GameSettings s) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("base",           s.base().name());
        map.put("gameMode",       s.gameMode().name());
        map.put("cardMode",       s.cardMode().name());
        map.put("randomOrder",    s.randomOrder());
        map.put("extraRow",       s.extraRow());
        map.put("connectedCells", s.connectedCells());
        map.put("botCount",       s.botCount());
        map.put("botStrategy",    s.botStrategy() != null ? s.botStrategy().name()
                                                           : BotStrategy.BALANCED.name());
        return map;
    }

    public static void apply(GameSettings.Builder builder, Map<String, Object> options) {
        if (options == null) return;
        for (var entry : options.entrySet()) {
            switch (entry.getKey()) {
                case "base"        -> builder.base(BaseVariant.valueOf(str(entry.getValue())));
                case "gameMode"    -> builder.gameMode(GameMode.valueOf(str(entry.getValue())));
                case "cardMode"    -> builder.cardMode(CardMode.valueOf(str(entry.getValue())));
                case "randomOrder"    -> builder.randomOrder(bool(entry.getValue()));
                case "extraRow"       -> builder.extraRow(bool(entry.getValue()));
                case "connectedCells" -> builder.connectedCells(bool(entry.getValue()));
                case "botCount"       -> builder.botCount(integer(entry.getValue()));
                case "botStrategy"    -> builder.botStrategy(BotStrategy.valueOf(str(entry.getValue())));
                default               -> log.warning("unknown game option '" + entry.getKey() + "', ignoring");
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