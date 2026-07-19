package nl.adg.qwixx.rules;

import jakarta.annotation.Nullable;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import nl.adg.qwixx.action.DiceCombination;
import nl.adg.qwixx.data.Cell;
import nl.adg.qwixx.data.Color;
import nl.adg.qwixx.data.Die;
import nl.adg.qwixx.data.RollResult;
import nl.adg.qwixx.state.ActiveTurnState;

class DiceRoller {

    private final Random random;

    DiceRoller(Random random) {
        this.random = random;
    }

    RollResult roll(List<Die> activeDice) {
        int[] whites = activeDice.stream()
                .filter(d -> d.color() == Color.WHITE)
                .mapToInt(d -> random.nextInt(d.faces()) + 1)
                .toArray();

        Map<Color, Integer> colored = new EnumMap<>(Color.class);
        for (Die die : activeDice) {
            if (die.color() != Color.WHITE) {
                colored.put(die.color(), random.nextInt(die.faces()) + 1);
            }
        }

        return new RollResult(whites[0], whites[1], colored);
    }

    @Nullable
    static DiceCombination resolveActiveCombo(RollResult roll, Cell cell, ActiveTurnState activePlayer, List<Die> activeDice) {
        if (activePlayer.colorDieUsed()) return null;

        if (!activePlayer.whiteWhiteUsed() && matchesWhiteWhite(roll, cell)) {
            return DiceCombination.WHITE_WHITE;
        }

        Color color = cell.color();
        Integer colorValue = roll.coloredDice().get(color);
        if (colorValue != null && matchesWhiteColor(roll, cell, colorValue)) {
            return DiceCombination.WHITE_COLOR;
        }

        return null;
    }

    static boolean matchesWhiteWhite(RollResult roll, Cell cell) {
        return String.valueOf(roll.white1() + roll.white2()).equals(cell.displayValue());
    }

    static boolean matchesWhiteColor(RollResult roll, Cell cell, int colorValue) {
        String display = cell.displayValue();
        return String.valueOf(roll.white1() + colorValue).equals(display)
                || String.valueOf(roll.white2() + colorValue).equals(display);
    }
}
