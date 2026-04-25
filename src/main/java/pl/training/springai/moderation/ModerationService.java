package pl.training.springai.moderation;

import org.springframework.ai.moderation.ModerationModel;
import org.springframework.ai.moderation.ModerationPrompt;
import org.springframework.stereotype.Service;

@Service
public class ModerationService {

    private final ModerationModel moderationModel;

    public ModerationService(ModerationModel moderationModel) {
        this.moderationModel = moderationModel;
    }

    public void moderate(String text) {
        var moderationResponse = moderationModel.call(new ModerationPrompt(text));
        var moderationResult = moderationResponse.getResult()
                .getOutput()
                .getResults()
                .getFirst();

        var categories = moderationResult.getCategories();

        if (categories.isHate() || categories.isHateThreatening()) {
            throw new ModerationException("Hate");
        }
        if (categories.isHarassment() || categories.isHarassmentThreatening()) {
            throw new ModerationException("Harassment");
        }
        if (categories.isViolence() || categories.isViolenceGraphic()) {
            throw new ModerationException("Violence");
        }
        if (categories.isSelfHarm() || categories.isSelfHarmIntent() || categories.isSelfHarmInstructions()) {
            throw new ModerationException("Self-harm");
        }
        if (categories.isSexual() || categories.isSexualMinors()) {
            throw new ModerationException("Sexual");
        }
    }

}
