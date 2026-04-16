package nl.adg.qwixx.state;

import java.util.Set;

public record RowState(Set<String> crossedCells, boolean lockCrossed) { }
