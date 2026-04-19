package nl.adg.qwixx.game;

import java.util.UUID;

public record Player(UUID id, String name) {
    public static Player of(String name) {
        return new Player(UUID.randomUUID(), name);
    }
}