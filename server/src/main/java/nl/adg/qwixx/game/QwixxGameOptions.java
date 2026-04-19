package nl.adg.qwixx.game;

import nl.adg.qwixx.state.CardMode;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public class QwixxGameOptions {

    private static final Logger log = Logger.getLogger(QwixxGameOptions.class.getName());

    private QwixxGameOptions() {}

    public static List<GameOption> all() {
        return List.of(
            GameOption.enumOption("gameMode", "Game mode",
                "ONLINE enforces turn order and dice rules on the server. " +
                "OFFLINE lets players use real dice — the server only tracks crossings and game-end conditions.",
                "ONLINE", List.of("ONLINE", "OFFLINE")),
            GameOption.enumOption("cardMode", "Card mode",
                "DETERMINISTIC gives all players identical row layouts. " +
                "PROBABILISTIC gives each player their own random layout.",
                "DETERMINISTIC", List.of("DETERMINISTIC", "PROBABILISTIC")),
            GameOption.boolOption("randomOrder", "Random order",
                "Shuffle the display values within each row."),
            GameOption.boolOption("extraRow", "Extra row",
                "Add ExtraBucket-tagged cells forming a sinus wave across the four rows.")
        );
    }

    public static void apply(GameSettings.Builder builder, Map<String, Object> options) {
        if (options == null) return;
        for (var entry : options.entrySet()) {
            switch (entry.getKey()) {
                case "gameMode"    -> builder.gameMode(GameMode.valueOf(str(entry.getValue())));
                case "cardMode"    -> builder.cardMode(CardMode.valueOf(str(entry.getValue())));
                case "randomOrder" -> builder.randomOrder(bool(entry.getValue()));
                case "extraRow"    -> builder.extraRow(bool(entry.getValue()));
                default            -> log.warning("unknown game option '" + entry.getKey() + "', ignoring");
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
}