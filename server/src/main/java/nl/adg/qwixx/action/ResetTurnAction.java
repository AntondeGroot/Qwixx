package nl.adg.qwixx.action;

import java.util.UUID;

public record ResetTurnAction(UUID playerId) implements GameAction {}