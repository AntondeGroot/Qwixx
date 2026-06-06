package nl.adg.qwixx.rules;

public class BigPointsScoringEngine extends StandardScoringEngine {

    private final int maxCrossesPerRow;

    public BigPointsScoringEngine(int maxCrossesPerRow) {
        this.maxCrossesPerRow = maxCrossesPerRow;
    }

    @Override
    protected int maxCrossesPerRow() {
        return maxCrossesPerRow;
    }
}
