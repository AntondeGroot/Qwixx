package nl.adg.qwixx.action;

import java.util.UUID;

// offline mode only: player manually records a punishment cross
public record TakePunishmentAction(UUID playerId) implements GameAction {}