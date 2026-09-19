package pl.training.model;

public record Metadata(
        String content,
        String model,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens
) {}
