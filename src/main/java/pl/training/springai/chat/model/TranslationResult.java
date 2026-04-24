package pl.training.springai.chat.model;

public record TranslationResult(
        String translatedText,
        String sourceLanguage,
        String targetLanguage
) {}
