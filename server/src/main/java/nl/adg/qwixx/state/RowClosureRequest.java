package nl.adg.qwixx.state;

import nl.adg.qwixx.data.Color;

import java.util.UUID;

public record RowClosureRequest(
    UUID playerId,
    Color rowColor
) {}
