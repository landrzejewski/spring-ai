package pl.training.springai.agents.evaluator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import pl.training.springai.agents.Action;

import java.util.ArrayList;
import java.util.List;

/**
 * Implementacja wzorca Evaluator-Optimizer.
 *
 * Evaluator-Optimizer:
 * Wzorzec, w ktorym jeden LLM generuje, a drugi ocenia.
 * Petla trwa dopoki wynik nie spelnia kryteriow lub osiagniesz max iteracji.
 *
 * Przebieg:
 * <pre>
 * [Generator] -> kod -> [Evaluator] -> score < threshold?
 *      ^                                    |
 *      |------ feedback --------------------|
 *                                           v
 *                                     score >= threshold -> Output
 * </pre>
 *
 * Przypadki uzycia:
 * - Code Generation: Generuj kod, ocen jakosc, popraw
 * - Content Creation: Napisz, ocen, popraw styl
 * - Translation: Przetlumacz, ocen dokladnosc, popraw
 * - Essay Writing: Napisz esej, ocen strukture, popraw
 *
 * Zalety:
 * - Iteracyjna poprawa jakosci
 * - Obiektywna ocena wedlug kryteriow
 * - Feedback kierunkuje poprawki
 *
 * Wady:
 * - Wiecej wywolan API (koszt, czas)
 * - Moze utknac w lokalnym minimum
 *
 * Uzycie:
 * <pre>
 * var optimizer = EvaluatorOptimizer.builder(chatClient)
 *     .criteria(EvaluationCriteria.CODE_CRITERIA)
 *     .maxIterations(3)
 *     .acceptanceThreshold(0.8)
 *     .build();
 *
 * var result = optimizer.execute("Write a function to check if a number is prime");
 * // result.accepted() - czy osiagnieto prog
 * // result.finalCode() - finalny kod
 * // result.iterations() - historia poprawek
 * </pre>
 */
public class EvaluatorOptimizer implements Action<String, OptimizedCodeResult> {

    private static final Logger LOGGER = LoggerFactory.getLogger(EvaluatorOptimizer.class);

    private final ChatClient chatClient;
    private final List<EvaluationCriteria> criteria;
    private final int maxIterations;
    private final double acceptanceThreshold;

    private EvaluatorOptimizer(ChatClient chatClient,
                               List<EvaluationCriteria> criteria,
                               int maxIterations,
                               double acceptanceThreshold) {
        this.chatClient = chatClient;
        this.criteria = criteria;
        this.maxIterations = maxIterations;
        this.acceptanceThreshold = acceptanceThreshold;
    }

    @Override
    public OptimizedCodeResult execute(String prompt) {
        LOGGER.info("Starting optimization for: {}", prompt.substring(0, Math.min(50, prompt.length())));

        List<OptimizedCodeResult.OptimizationIteration> iterations = new ArrayList<>();
        String currentCode = null;
        String feedback = null;
        double currentScore = 0.0;

        for (int i = 1; i <= maxIterations; i++) {
            LOGGER.info("=== Iteration {} of {} ===", i, maxIterations);

            // 1. Generator tworzy/poprawia kod
            LOGGER.info("Generating code...");
            currentCode = generate(prompt, currentCode, feedback);

            // 2. Evaluator ocenia
            LOGGER.info("Evaluating code...");
            var evaluation = evaluate(currentCode);
            currentScore = evaluation.overallScore();
            feedback = evaluation.feedback();

            LOGGER.info("Score: {} (threshold: {})", currentScore, acceptanceThreshold);

            // 3. Zapisz iteracje
            iterations.add(new OptimizedCodeResult.OptimizationIteration(
                    i, currentCode, currentScore, feedback, evaluation.criteriaScores()
            ));

            // 4. Sprawdz czy akceptowalne
            if (currentScore >= acceptanceThreshold) {
                LOGGER.info("Accepted! Final score: {}", currentScore);
                return new OptimizedCodeResult(
                        prompt, currentCode, iterations, i, currentScore, true
                );
            }

            LOGGER.info("Not accepted. Feedback: {}", feedback.substring(0, Math.min(100, feedback.length())));
        }

        // Max iteracji osiagniete - zwroc najlepszy wynik
        LOGGER.info("Max iterations reached. Final score: {} (not accepted)", currentScore);
        return new OptimizedCodeResult(
                prompt, currentCode, iterations, maxIterations, currentScore, false
        );
    }

    /**
     * Generator tworzy lub poprawia kod.
     *
     * Pierwsza iteracja: Tworzy kod od zera
     * Kolejne iteracje: Poprawia na podstawie feedbacku
     */
    private String generate(String prompt, String previousCode, String feedback) {
        if (previousCode == null) {
            // Pierwsza generacja
            return chatClient.prompt()
                    .system("""
                            You are an expert programmer.
                            Write clean, efficient, well-documented code.
                            Follow best practices and handle edge cases.
                            """)
                    .user(prompt)
                    .call()
                    .content();
        } else {
            // Poprawa na podstawie feedbacku
            return chatClient.prompt()
                    .system("""
                            You are an expert programmer specializing in code improvement.
                            Improve the given code based on the feedback provided.
                            Focus on addressing the specific issues mentioned in the feedback.
                            Keep the overall structure but fix the identified problems.
                            """)
                    .user("""
                            Original task: %s

                            Current code:
                            ```
                            %s
                            ```

                            Feedback to address:
                            %s

                            Please provide an improved version of the code that addresses the feedback.
                            Return only the improved code.
                            """.formatted(prompt, previousCode, feedback))
                    .call()
                    .content();
        }
    }

    /**
     * Evaluator ocenia kod wedlug kryteriow.
     */
    private EvaluationResult evaluate(String code) {
        var criteriaText = criteria.stream()
                .map(c -> "- %s (weight: %.0f%%): %s. Minimum acceptable: %.0f%%".formatted(
                        c.criteriaName(),
                        c.weight() * 100,
                        c.description(),
                        c.minimumScore() * 100))
                .reduce("", (a, b) -> a + "\n" + b);

        return chatClient.prompt()
                .system("""
                        You are a code review expert.

                        Evaluate the given code according to these criteria:
                        %s

                        For each criterion, provide:
                        - criteriaName: the name of the criterion
                        - score: a number from 0.0 to 1.0
                        - explanation: brief (1-2 sentences) explanation of the score

                        Also provide:
                        - overallScore: weighted average of all criteria scores
                        - feedback: specific, actionable improvements needed (2-3 sentences)

                        Be fair but rigorous in your evaluation.
                        """.formatted(criteriaText))
                .user("Code to evaluate:\n```\n" + code + "\n```")
                .call()
                .entity(EvaluationResult.class);
    }

    /**
     * Wewnetrzny record dla wyniku ewaluacji przez LLM.
     */
    private record EvaluationResult(
            double overallScore,
            String feedback,
            List<OptimizedCodeResult.CriteriaScore> criteriaScores
    ) {}

    /**
     * Tworzy builder dla Evaluator-Optimizer.
     *
     * @param chatClient ChatClient do uzycia
     * @return Builder
     */
    public static Builder builder(ChatClient chatClient) {
        return new Builder(chatClient);
    }

    /**
     * Builder dla Evaluator-Optimizer.
     */
    public static class Builder {
        private final ChatClient chatClient;
        private List<EvaluationCriteria> criteria = EvaluationCriteria.CODE_CRITERIA;
        private int maxIterations = 3;
        private double acceptanceThreshold = 0.8;

        public Builder(ChatClient chatClient) {
            this.chatClient = chatClient;
        }

        /**
         * Ustawia kryteria oceny.
         *
         * @param criteria Lista kryteriow
         * @return Builder
         */
        public Builder criteria(List<EvaluationCriteria> criteria) {
            this.criteria = criteria;
            return this;
        }

        /**
         * Ustawia maksymalna liczbe iteracji.
         *
         * Wiecej iteracji = potencjalnie lepsza jakosc, ale wyzszy koszt.
         *
         * @param max Maksymalna liczba iteracji
         * @return Builder
         */
        public Builder maxIterations(int max) {
            this.maxIterations = max;
            return this;
        }

        /**
         * Ustawia prog akceptacji.
         *
         * Jesli srednia wazona ocena >= prog, kod jest akceptowany.
         *
         * @param threshold Prog akceptacji (0.0-1.0)
         * @return Builder
         */
        public Builder acceptanceThreshold(double threshold) {
            this.acceptanceThreshold = threshold;
            return this;
        }

        /**
         * Buduje Evaluator-Optimizer.
         *
         * @return Gotowy EvaluatorOptimizer
         */
        public EvaluatorOptimizer build() {
            return new EvaluatorOptimizer(chatClient, criteria, maxIterations, acceptanceThreshold);
        }
    }
}
