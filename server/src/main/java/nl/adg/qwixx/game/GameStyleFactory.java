package nl.adg.qwixx.game;

import nl.adg.qwixx.data.Die;
import nl.adg.qwixx.data.Row;
import nl.adg.qwixx.rules.ScoringEngine;
import nl.adg.qwixx.rules.TurnRules;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface GameStyleFactory {
    GameSettings settings();
    Map<UUID, List<Row>> buildRows(List<UUID> players);
    List<Die> buildDice();
    TurnRules buildTurnRules();
    ScoringEngine buildScoringEngine();
    VariantData buildVariantData();
}