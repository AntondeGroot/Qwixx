package nl.adg.qwixx.action;

import java.util.UUID;

public record RollAction(UUID playerId) implements GameAction {}
