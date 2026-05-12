package nl.adg.qwixx.action;

import java.util.UUID;

public sealed interface GameAction permits
        RollAction,
        CrossCellAction,
        DeclareLockIntentAction,
        UndoLastCrossAction,
        GiveUpAction,
        ResetTurnAction,
        EndTurnAction,
        TakePunishmentAction {

    UUID playerId();
}