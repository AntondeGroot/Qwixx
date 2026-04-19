package nl.adg.qwixx.action;

import java.util.UUID;

public record EndTurnAction(UUID playerId) implements GameAction {}