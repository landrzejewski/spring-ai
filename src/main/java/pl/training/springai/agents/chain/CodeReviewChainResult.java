package pl.training.springai.agents.chain;

import java.util.List;

/**
 * Wynik lancucha Code Review.
 *
 * Lancuch Code Review sklada sie z 3 krokow:
 * 1. Analiza - identyfikacja problemow w kodzie (code smells, bugs)
 * 2. Sugestie - propozycje usprawnien dla kazdego problemu
 * 3. Refaktoryzacja - zrefaktoryzowany kod po zastosowaniu usprawnien
 *
 * Przyklad uzycia:
 * <pre>
 * var result = codeReviewChain.execute(sourceCode);
 * System.out.println("Znaleziono problemow: " + result.analysisFindings().size());
 * System.out.println("Sugestii: " + result.improvementSuggestions().size());
 * System.out.println("Nowy kod:\n" + result.refactoredCode());
 * </pre>
 *
 * @param originalCode Oryginalny kod zrodlowy przekazany do analizy
 * @param analysisFindings Lista znalezionych problemow (code smells, bugs, violations)
 * @param improvementSuggestions Lista sugestii usprawnien (1:1 z findings lub ogolne)
 * @param refactoredCode Zrefaktoryzowany kod po zastosowaniu usprawnien
 * @param summary Krotkie podsumowanie zmian (co zostalo poprawione)
 */
public record CodeReviewChainResult(
        String originalCode,
        List<String> analysisFindings,
        List<String> improvementSuggestions,
        String refactoredCode,
        String summary
) {}
