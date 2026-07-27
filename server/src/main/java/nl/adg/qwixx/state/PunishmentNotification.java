package nl.adg.qwixx.state;

import java.util.UUID;

/** A UI notification that {@code playerId} reached the maximum punishments, so the game will end
 *  after the current turn resolves. Unlike a row closure it carries no colour — just the declarant. */
public record PunishmentNotification(UUID playerId) {}
