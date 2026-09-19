package pl.training.agents.evaluator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import pl.training.agents.Action;

import java.util.ArrayList;
import java.util.List;

/**
 * One model generates, another scores, and the feedback goes back to the generator.
 * <pre>
 * [Generator] -> output -> [Evaluator] -> score below threshold?
 *      ^                                       |
 *      |-------------- feedback ---------------|
 *                                              v
 *                                score at or above threshold -> Output
 * </pre>
 * This is the only pattern in the package with a feedback edge: the number of steps is decided at
 * runtime by the scores, not fixed in advance.
 * <p>
 * It works because the evaluation is structured output - a number per criterion plus written
 * feedback - which the next generation prompt can act on. Asking a model to "improve this" without
 * telling it what was wrong changes the output without improving it.
 * <p>
 * The costs are real: every iteration is two model calls, and the loop can plateau, polishing an
 * approach that was wrong from the start rather than reconsidering it. maxIterations bounds both.
 * <p>
 * Spring AI's own Evaluator API (RelevancyEvaluator, FactCheckingEvaluator) is the framework's
 * take on the evaluation half of this idea, with the criteria and prompts provided.
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

            // 1. Generate, or revise using the previous feedback
            LOGGER.info("Generating code...");
            currentCode = generate(prompt, currentCode, feedback);

            // 2. Score it against the criteria
            LOGGER.info("Evaluating code...");
            var evaluation = evaluate(currentCode);
            currentScore = evaluation.overallScore();
            feedback = evaluation.feedback();

            LOGGER.info("Score: {} (threshold: {})", currentScore, acceptanceThreshold);

            // 3. Record the iteration, so the evolution stays inspectable
            iterations.add(new OptimizedCodeResult.OptimizationIteration(
                    i, currentCode, currentScore, feedback, evaluation.criteriaScores()
            ));

            // 4. Stop as soon as the threshold is met
            if (currentScore >= acceptanceThreshold) {
                LOGGER.info("Accepted! Final score: {}", currentScore);
                return new OptimizedCodeResult(
                        prompt, currentCode, iterations, i, currentScore, true
                );
            }

            LOGGER.info("Not accepted. Feedback: {}", feedback.substring(0, Math.min(100, feedback.length())));
        }

        // Budget exhausted: return the best iteration rather than the last one
        LOGGER.info("Max iterations reached. Final score: {} (not accepted)", currentScore);
        return new OptimizedCodeResult(
                prompt, currentCode, iterations, maxIterations, currentScore, false
        );
    }

    /**
     * The generation call. The first iteration works from the request alone; later ones are given
     * the previous attempt and the evaluator's feedback, which is what turns a retry into a
     * revision.
     */
    private String generate(String prompt, String previousCode, String feedback) {
        if (previousCode == null) {
            // First attempt: nothing to revise yet
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
            // Revision: the previous attempt plus what the evaluator objected to
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
     * The evaluation call. The criteria, their weights and their descriptions are rendered into
     * the prompt, and the verdict comes back as structured output so the loop can compare it
     * against a threshold instead of parsing prose.
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
     * The structured output type of the evaluation call.
     */
    private record EvaluationResult(
            double overallScore,
            String feedback,
            List<OptimizedCodeResult.CriteriaScore> criteriaScores
    ) {}

    /**
     * @param chatClient the client used for both generation and evaluation
     * @return a builder
     */
    public static Builder builder(ChatClient chatClient) {
        return new Builder(chatClient);
    }

    /**
     * Assembles the loop.
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
         * @param criteria the dimensions to score against; their weights should sum to 1.0
         * @return the builder
         */
        public Builder criteria(List<EvaluationCriteria> criteria) {
            this.criteria = criteria;
            return this;
        }

        /**
         * Each iteration costs two model calls, so this is the cost ceiling as much as the quality
         * ceiling.
         *
         * @param max the maximum number of iterations
         * @return the builder
         */
        public Builder maxIterations(int max) {
            this.maxIterations = max;
            return this;
        }

        /**
         * @param threshold the weighted score at which the loop stops, 0.0 to 1.0
         * @return the builder
         */
        public Builder acceptanceThreshold(double threshold) {
            this.acceptanceThreshold = threshold;
            return this;
        }

        /**
         * Builds the loop.
         *
         * @return Gotowy EvaluatorOptimizer
         */
        public EvaluatorOptimizer build() {
            return new EvaluatorOptimizer(chatClient, criteria, maxIterations, acceptanceThreshold);
        }
    }
}
