package net.azisaba.frontier.domain;

public record TutorialStatus(
        boolean enabled,
        boolean completed,
        int currentIndex,
        int totalSteps,
        TutorialStepDefinition currentStep
) {
}
