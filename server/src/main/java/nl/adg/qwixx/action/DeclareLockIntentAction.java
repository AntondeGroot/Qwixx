package nl.adg.qwixx.action;

import java.util.UUID;

public record DeclareLockIntentAction(UUID playerId, int rowIndex) implements GameAction {}