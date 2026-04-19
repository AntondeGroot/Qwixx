package nl.adg.qwixx.rules;

import nl.adg.qwixx.action.GameAction;
import nl.adg.qwixx.state.GameState;

import java.util.List;
import java.util.UUID;

public interface TurnRules {
    List<GameAction> getValidActions(GameState state, UUID playerId);
    GameState apply(GameState state, GameAction action);
    boolean isGameOver(GameState state);
}