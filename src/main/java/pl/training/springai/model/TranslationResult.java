package pl.training.springai.model;

public record TranslationResult(
        String translatedText,
        String sourceLanguage,
        String targetLanguage
) {}
