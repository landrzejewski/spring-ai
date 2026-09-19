package pl.training.model;

import java.util.List;

public record SummaryResult(
        String summary,
        List<String> keyPoints,
        Integer wordCount
) {}
