package nl.adg.qwixx.state;

import nl.adg.qwixx.data.Color;

public record RowClosureRequest(
    String playerName,
    Color rowColor
) {}
