package pl.training.springai.chat.model;

import java.util.List;

public record SummaryResult(
        String summary,
        List<String> keyPoints,
        int wordCount
) {}
