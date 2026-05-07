package nl.adg.qwixx.game;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record LongoVariantData(Map<UUID, List<Integer>> bonusNumbersPerPlayer) implements VariantData {
    public LongoVariantData {
        bonusNumbersPerPlayer = Map.copyOf(bonusNumbersPerPlayer);
    }
}