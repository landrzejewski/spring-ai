package pl.training.springai.chat.model;

public record PromptRequest(String userPromptText, String context) {
}
