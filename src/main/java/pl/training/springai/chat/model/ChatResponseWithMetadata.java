package pl.training.springai.chat.model;

public record ChatResponseWithMetadata(
        String content,
        String model,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens
) {}
