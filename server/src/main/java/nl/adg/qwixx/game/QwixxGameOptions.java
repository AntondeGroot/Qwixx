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
            GameOption.enumOption("base", "gameOption.base", "gameOption.baseDescription",
                "STANDARD", List.of("STANDARD", "LONGO")),
            GameOption.enumOption("gameMode", "gameOption.gameMode", "gameOption.gameModeDescription",
                "ONLINE", List.of("ONLINE", "OFFLINE")),
            GameOption.enumOption("cardMode", "gameOption.cardMode", "gameOption.cardModeDescription",
                "DETERMINISTIC", List.of("DETERMINISTIC", "PROBABILISTIC")),
            GameOption.boolOption("randomOrder", "gameOption.randomOrder", "gameOption.randomOrderDescription"),
            GameOption.boolOption("extraRow", "gameOption.extraRow", "gameOption.extraRowDescription"),
            GameOption.boolOption("connectedCells", "gameOption.connectedCells", "gameOption.connectedCellsDescription")
        );
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
}