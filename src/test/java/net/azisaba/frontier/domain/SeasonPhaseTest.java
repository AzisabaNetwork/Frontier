package net.azisaba.frontier.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeasonPhaseTest {
    @Test
    void allowsOnlyForwardTransitions() {
        assertTrue(SeasonPhase.PRESEASON.canTransitionTo(SeasonPhase.OPENING));
        assertTrue(SeasonPhase.OPENING.canTransitionTo(SeasonPhase.ACTIVE));
        assertTrue(SeasonPhase.ACTIVE.canTransitionTo(SeasonPhase.FINALE));
        assertTrue(SeasonPhase.FINALE.canTransitionTo(SeasonPhase.ARCHIVED));
        assertFalse(SeasonPhase.ACTIVE.canTransitionTo(SeasonPhase.OPENING));
        assertFalse(SeasonPhase.ARCHIVED.canTransitionTo(SeasonPhase.ACTIVE));
    }
}
