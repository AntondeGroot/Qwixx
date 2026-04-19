package nl.adg.qwixx.action;

import java.util.UUID;

public record CrossLockAction(UUID playerId, int rowIndex) implements GameAction {}