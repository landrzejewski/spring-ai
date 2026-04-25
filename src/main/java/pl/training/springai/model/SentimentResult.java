package pl.training.springai.model;

/**
 * Wynik analizy sentymentu - structured output binding.
 *
 * @param score Sentyment od -1.0 (negatywny) do +1.0 (pozytywny)
 * @param explanation Krotkie wyjasnienie wyniku
 */
public record SentimentResult(
        double score,
        String explanation
) {}
