package pl.training.springai.model;

import java.util.List;

public record SummaryResult(
        String summary,
        List<String> keyPoints,
        int wordCount
) {}
