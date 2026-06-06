package nl.adg.qwixx.action;

import java.util.UUID;

public record UndoLastCrossAction(UUID playerId) implements GameAction {}
