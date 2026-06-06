package nl.adg.qwixx.state;

import java.util.UUID;
import nl.adg.qwixx.data.Color;

public record ClosureNotification(
    UUID playerId,
    Color rowColor
) {}
