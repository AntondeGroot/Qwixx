package nl.adg.qwixx.game;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import nl.adg.qwixx.data.Die;
import nl.adg.qwixx.data.Row;
import nl.adg.qwixx.rules.ScoringEngine;
import nl.adg.qwixx.rules.TurnRules;
import nl.adg.qwixx.state.VariantData;

public interface GameStyleFactory {
    GameSettings settings();
    Map<UUID, List<Row>> buildRows(List<UUID> players);
    List<Die> buildDice();
    TurnRules buildTurnRules();
    ScoringEngine buildScoringEngine();
    VariantData buildVariantData(List<UUID> playerIds);
}
