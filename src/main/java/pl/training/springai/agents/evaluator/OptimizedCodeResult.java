package pl.training.springai.agents.evaluator;

import java.util.List;

/**
 * Wynik wzorca Evaluator-Optimizer.
 *
 * Evaluator-Optimizer:
 * - Generator LLM tworzy rozwiazanie
 * - Evaluator LLM ocenia rozwiazanie wedlug kryteriow
 * - Jesli ocena < prog -> Generator poprawia na podstawie feedbacku
 * - Petla trwa az do akceptacji lub osiagniecia max iteracji
 *
 * Roznica od Chain:
 * - Chain: stala liczba krokow, bez petli zwrotnej
 * - Evaluator-Optimizer: iteracyjna petla z feedbackiem, dynamiczna liczba krokow
 *
 * Zawiera historie iteracji - mozna zobaczyc jak kod ewoluowal:
 * - Iteracja 1: Podstawowa implementacja (score: 0.6)
 * - Iteracja 2: Dodano obsluge edge cases (score: 0.75)
 * - Iteracja 3: Poprawiono czytelnosc (score: 0.85) - akceptacja
 *
 * Przyklad uzycia:
 * <pre>
 * var result = optimizer.execute("Write a sorting function");
 *
 * System.out.println("Iterations: " + result.totalIterations());
 * System.out.println("Final score: " + result.finalScore());
 * System.out.println("Accepted: " + result.accepted());
 *
 * for (var iteration : result.iterations()) {
 *     System.out.println("Iteration " + iteration.iterationNumber() +
 *         ": score=" + iteration.score());
 *     System.out.println("Feedback: " + iteration.feedback());
 * }
 *
 * System.out.println("Final code:\n" + result.finalCode());
 * </pre>
 *
 * @param originalPrompt Oryginalny prompt uzytkownika (zadanie)
 * @param finalCode Finalny zoptymalizowany kod
 * @param iterations Lista iteracji (historia optymalizacji)
 * @param totalIterations Calkowita liczba iteracji
 * @param finalScore Finalna ocena (srednia wazona wszystkich kryteriow)
 * @param accepted Czy wynik zostal zaakceptowany (finalScore >= threshold)
 */
public record OptimizedCodeResult(
        String originalPrompt,
        String finalCode,
        List<OptimizationIteration> iterations,
        int totalIterations,
        double finalScore,
        boolean accepted
) {
    /**
     * Pojedyncza iteracja optymalizacji.
     *
     * Kazda iteracja zawiera:
     * - Numer iteracji (od 1)
     * - Wygenerowany kod w tej iteracji
     * - Ocena kodu
     * - Feedback co poprawic
     * - Szczegolowe oceny kazdego kryterium
     *
     * @param iterationNumber Numer iteracji (1, 2, 3, ...)
     * @param generatedCode Kod wygenerowany w tej iteracji
     * @param score Srednia wazona ocena (0.0-1.0)
     * @param feedback Feedback od ewaluatora (co poprawic)
     * @param criteriaScores Oceny poszczegolnych kryteriow
     */
    public record OptimizationIteration(
            int iterationNumber,
            String generatedCode,
            double score,
            String feedback,
            List<CriteriaScore> criteriaScores
    ) {}

    /**
     * Ocena pojedynczego kryterium.
     *
     * @param criteriaName Nazwa kryterium (np. "Correctness")
     * @param score Ocena od 0.0 do 1.0
     * @param explanation Wyjasnienie dlaczego taka ocena
     */
    public record CriteriaScore(
            String criteriaName,
            double score,
            String explanation
    ) {}
}
