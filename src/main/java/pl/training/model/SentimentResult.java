package pl.training.model;

public record SentimentResult(
        double score,
        String explanation
) {}
