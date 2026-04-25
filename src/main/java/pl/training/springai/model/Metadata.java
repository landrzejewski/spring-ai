package pl.training.springai.model;

public record Metadata(
        String content,
        String model,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens
) {}
