package pl.training.springai.agents.evaluator;

import java.util.List;

/**
 * Kryteria oceny dla wzorca Evaluator-Optimizer.
 *
 * Evaluator-Optimizer to wzorzec iteracyjny, w ktorym:
 * 1. Generator tworzy rozwiazanie
 * 2. Evaluator ocenia wedlug zdefiniowanych kryteriow
 * 3. Jesli ocena < prog -> Generator poprawia na podstawie feedbacku
 * 4. Petla trwa az do akceptacji lub max iteracji
 *
 * Kryteria definiuja:
 * - Co oceniamy (nazwa i opis)
 * - Jak wazne jest kryterium (waga)
 * - Jaki minimalny wynik jest akceptowalny
 *
 * Przyklad kryteriow dla kodu:
 * <pre>
 * var criteria = List.of(
 *     new EvaluationCriteria("Correctness", "Kod dziala poprawnie", 0.4, 0.8),
 *     new EvaluationCriteria("Readability", "Kod jest czytelny", 0.3, 0.7),
 *     new EvaluationCriteria("Efficiency", "Kod jest wydajny", 0.3, 0.6)
 * );
 * </pre>
 *
 * @param criteriaName Nazwa kryterium (np. "Correctness", "Code Quality")
 * @param description Opis czego szukamy / co oceniamy
 * @param weight Waga kryterium od 0.0 do 1.0 (suma wszystkich wag powinna = 1.0)
 * @param minimumScore Minimalny akceptowalny wynik dla tego kryterium (0.0-1.0)
 */
public record EvaluationCriteria(
        String criteriaName,
        String description,
        double weight,
        double minimumScore
) {
    /**
     * Predefiniowane kryteria dla oceny kodu.
     *
     * Wagi:
     * - Correctness: 30% (najwazniejsze - kod musi dzialac)
     * - Readability: 20% (kod bedzie czytany wielokrotnie)
     * - Efficiency: 20% (wydajnosc ma znaczenie)
     * - Maintainability: 20% (latwiejsze utrzymanie)
     * - Security: 10% (bezpieczenstwo podstawowe)
     */
    public static final List<EvaluationCriteria> CODE_CRITERIA = List.of(
            new EvaluationCriteria(
                    "Correctness",
                    "Code produces correct output for all cases including edge cases",
                    0.30,
                    0.8
            ),
            new EvaluationCriteria(
                    "Readability",
                    "Code is clear, well-documented, with meaningful names and proper formatting",
                    0.20,
                    0.7
            ),
            new EvaluationCriteria(
                    "Efficiency",
                    "Code has optimal time and space complexity for the problem",
                    0.20,
                    0.6
            ),
            new EvaluationCriteria(
                    "Maintainability",
                    "Code follows SOLID principles, is modular and easy to extend",
                    0.20,
                    0.6
            ),
            new EvaluationCriteria(
                    "Security",
                    "Code handles edge cases safely and avoids common vulnerabilities",
                    0.10,
                    0.7
            )
    );

    /**
     * Predefiniowane kryteria dla oceny tekstu/contentu.
     */
    public static final List<EvaluationCriteria> CONTENT_CRITERIA = List.of(
            new EvaluationCriteria(
                    "Accuracy",
                    "Content is factually correct and well-researched",
                    0.35,
                    0.8
            ),
            new EvaluationCriteria(
                    "Clarity",
                    "Content is clear, well-structured, and easy to understand",
                    0.30,
                    0.7
            ),
            new EvaluationCriteria(
                    "Completeness",
                    "Content covers all important aspects of the topic",
                    0.20,
                    0.7
            ),
            new EvaluationCriteria(
                    "Engagement",
                    "Content is engaging and holds reader's attention",
                    0.15,
                    0.6
            )
    );
}
