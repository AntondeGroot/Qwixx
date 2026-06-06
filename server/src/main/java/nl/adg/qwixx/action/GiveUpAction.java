package nl.adg.qwixx.action;

import java.util.UUID;

public record GiveUpAction(UUID playerId) implements GameAction {}
