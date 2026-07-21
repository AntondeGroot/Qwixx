package nl.adg.qwixx.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import nl.adg.qwixx.bot.BotProfile;
import nl.adg.qwixx.bot.BotStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class GameSettingsTest {

    @Test
    void accessorsRoundTripTheirBuilderInputs() {
        // Two instances so every boolean accessor is asserted in both states: the "on" build pins the
        // fields that default to false (and flips seeOtherCards off), the "off" build pins the defaults.
        // The fluent setters are chained mid-chain on purpose — if any returned null instead of `this`
        // the next call in the chain would NPE.
        GameSettings on = GameSettings.builder()
                .connectedCells(true)
                .mixedColors(true)
                .seeOtherCards(false)
                .botProfiles(List.of(BotProfile.DEFAULT))
                .build();
        GameSettings off = GameSettings.builder().build();

        assertTrue(on.connectedCells());
        assertTrue(on.mixedColors());
        assertFalse(on.seeOtherCards());
        assertEquals(List.of(BotProfile.DEFAULT), on.botProfiles());

        assertFalse(off.connectedCells());
        assertFalse(off.mixedColors());
        assertTrue(off.seeOtherCards(), "seeOtherCards defaults to true");
        assertTrue(off.botProfiles().isEmpty());
    }

    @Test
    void profileForBotPrefersPerSeatOverrideThenStrategyThenDefault() {
        // A per-seat override and a strategy that resolve to *different* profiles, so each resolution
        // branch returns a distinct value: seat 0 must take the override, seat 1 (no override) must fall
        // through to the strategy, and with no strategy at all the hard DEFAULT must win.
        GameSettings withOverride = GameSettings.builder()
                .botStrategy(BotStrategy.MOST_POINTS)
                .botProfiles(List.of(BotProfile.DEFAULT))
                .build();

        assertEquals(BotProfile.DEFAULT, withOverride.profileForBot(0), "seat 0 has an override");
        assertEquals(BotStrategy.MOST_POINTS.profile(), withOverride.profileForBot(1),
                "seat 1 has no override, so the strategy profile applies");

        GameSettings noStrategy = GameSettings.builder().botStrategy(null).build();
        assertEquals(BotProfile.DEFAULT, noStrategy.profileForBot(0),
                "no override and no strategy falls back to BotProfile.DEFAULT");
    }

    // ── build() incompatibility guards ────────────────────────────────────────────────────
    // Each combination below is minimal and trips exactly ONE guard (no earlier guard short-circuits
    // it), so every boolean operand in that guard's condition is load-bearing for the thrown exception.
    // randomOrder + bigPoints (guard 1) is already covered in QwixxGameOptionsTest.

    @ParameterizedTest(name = "{0} cannot be combined")
    @MethodSource("forbiddenCombinations")
    void forbiddenOptionCombinationsAreRejected(String description, Consumer<GameSettings.Builder> combination) {
        GameSettings.Builder builder = GameSettings.builder();
        combination.accept(builder);
        assertThrows(IllegalArgumentException.class, builder::build);
    }

    private static Arguments forbidden(String description, Consumer<GameSettings.Builder> combination) {
        return Arguments.of(description, combination);
    }

    private static Stream<Arguments> forbiddenCombinations() {
        return Stream.of(
                // guard 2: luckyCross && bigPoints
                forbidden("luckyCross + bigPoints", b -> b.luckyCross(true).bigPoints(true)),
                // guard 3: doubleA && doubleB
                forbidden("doubleA + doubleB", b -> b.doubleA(true).doubleB(true)),
                // guard 4: (doubleA || doubleB) && (bigPoints || luckyCross || connectedCells)
                forbidden("doubleA + bigPoints", b -> b.doubleA(true).bigPoints(true)),
                forbidden("doubleA + luckyCross", b -> b.doubleA(true).luckyCross(true)),
                forbidden("doubleA + connectedCells", b -> b.doubleA(true).connectedCells(true)),
                forbidden("doubleB + bigPoints", b -> b.doubleB(true).bigPoints(true)),
                forbidden("doubleB + luckyCross", b -> b.doubleB(true).luckyCross(true)),
                forbidden("doubleB + connectedCells", b -> b.doubleB(true).connectedCells(true)),
                // guard 5: bonusB && (bigPoints|doubleA|doubleB|connectedCells|luckyCross|extraRow|randomOrder|bonusA)
                forbidden("bonusB + bigPoints", b -> b.bonusB(true).bigPoints(true)),
                forbidden("bonusB + doubleA", b -> b.bonusB(true).doubleA(true)),
                forbidden("bonusB + doubleB", b -> b.bonusB(true).doubleB(true)),
                forbidden("bonusB + connectedCells", b -> b.bonusB(true).connectedCells(true)),
                forbidden("bonusB + luckyCross", b -> b.bonusB(true).luckyCross(true)),
                forbidden("bonusB + extraRow", b -> b.bonusB(true).extraRow(true)),
                forbidden("bonusB + randomOrder", b -> b.bonusB(true).randomOrder(true)),
                forbidden("bonusB + bonusA", b -> b.bonusB(true).bonusA(true)),
                // guard 6: bonusA && (bigPoints|doubleA|doubleB|connectedCells|luckyCross|extraRow|randomOrder)
                //          (bonusA + bonusB is caught by guard 5 above, so guard 6 does not list bonusB)
                forbidden("bonusA + bigPoints", b -> b.bonusA(true).bigPoints(true)),
                forbidden("bonusA + doubleA", b -> b.bonusA(true).doubleA(true)),
                forbidden("bonusA + doubleB", b -> b.bonusA(true).doubleB(true)),
                forbidden("bonusA + connectedCells", b -> b.bonusA(true).connectedCells(true)),
                forbidden("bonusA + luckyCross", b -> b.bonusA(true).luckyCross(true)),
                forbidden("bonusA + extraRow", b -> b.bonusA(true).extraRow(true)),
                forbidden("bonusA + randomOrder", b -> b.bonusA(true).randomOrder(true)));
    }
}
