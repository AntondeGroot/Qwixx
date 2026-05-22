package nl.adg.qwixx.state;

import nl.adg.qwixx.data.Color;

import java.util.UUID;

public record ClosureNotification(
    UUID playerId,
    Color rowColor
) {}
