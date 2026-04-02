package net.azisaba.frontier.domain;

public record TutorialStepDefinition(
        String id,
        String title,
        String description,
        String objective,
        String actionKey,
        long rewardCoins,
        long rewardSp
) {
}
