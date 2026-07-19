package nl.adg.qwixx.game;

import jakarta.annotation.Nullable;
import java.util.UUID;

public record Player(UUID id, String name, @Nullable String profilePic) {
    public static Player of(String name) {
        return new Player(UUID.randomUUID(), name, null);
    }
}
