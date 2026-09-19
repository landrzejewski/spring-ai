package pl.training.agents.evaluator;

import java.util.List;

/**
 * The outcome of an evaluator-optimizer run, iteration history included.
 * <p>
 * The history is the interesting part: scores that climb (0.60 -&gt; 0.75 -&gt; 0.85) show the
 * feedback is landing, while scores that stay flat mean the loop is burning calls without
 * improving anything - a signal to sharpen the criteria rather than to raise maxIterations.
 * <p>
 * {@code accepted} distinguishes "good enough" from "ran out of iterations", which the final score
 * alone does not.
 *
 * @param originalPrompt what was asked for
 * @param finalCode the best output produced
 * @param iterations the full history
 * @param totalIterations how many rounds were run
 * @param finalScore the weighted score of the final output
 * @param accepted whether the threshold was reached
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
     * @param iterationNumber the round, starting at 1
     * @param generatedCode what the generator produced
     * @param score the weighted score across all criteria
     * @param feedback what the evaluator objected to; this is fed to the next generation
     * @param criteriaScores the per-criterion breakdown
     */
    public record OptimizationIteration(
            int iterationNumber,
            String generatedCode,
            double score,
            String feedback,
            List<CriteriaScore> criteriaScores
    ) {}

    /**
     * @param criteriaName the dimension scored
     * @param score 0.0 to 1.0
     * @param explanation why the evaluator gave this score
     */
    public record CriteriaScore(
            String criteriaName,
            double score,
            String explanation
    ) {}
}
