package nl.adg.qwixx.rules;

import nl.adg.qwixx.state.SheetLayout;
import nl.adg.qwixx.state.SheetProgress;

@FunctionalInterface
public interface ScoringEngine {
    ScoreCard calculate(SheetLayout layout, SheetProgress progress);
}
