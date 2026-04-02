package net.azisaba.frontier.domain;

public record TutorialProgressUpdate(
        boolean advanced,
        boolean completedTutorial,
        TutorialStepDefinition completedStep,
        TutorialStepDefinition nextStep,
        long rewardCoins,
        long rewardSp
) {
    public static TutorialProgressUpdate none() {
        return new TutorialProgressUpdate(false, false, null, null, 0L, 0L);
    }
}
