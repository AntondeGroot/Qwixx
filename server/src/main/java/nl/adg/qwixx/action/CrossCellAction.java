package nl.adg.qwixx.action;

import java.util.UUID;

public record CrossCellAction(
        UUID playerId,
        int rowIndex,
        String cellId,
        DiceCombination combination
) implements GameAction {}