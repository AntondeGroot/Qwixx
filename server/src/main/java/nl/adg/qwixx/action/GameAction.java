package nl.adg.qwixx.action;

import java.util.UUID;

public sealed interface GameAction permits
        RollAction,
        CrossCellAction,
        DeclareLockIntentAction,
        CrossLockAction,
        UndoLastCrossAction,
        GiveUpAction,
        ResetTurnAction,
        EndTurnAction,
        TakePunishmentAction {

    UUID playerId();
}