package pl.training.agents.evaluator;

import java.util.List;

/**
 * One dimension the evaluator scores against.
 * <p>
 * The description is not documentation - it is injected into the evaluation prompt, so it is what
 * the judging model is actually told to look for. Vague descriptions produce vague scores, which
 * is the usual reason an evaluator-optimizer loop fails to improve anything.
 * <p>
 * Weights turn several scores into one decision; they should sum to 1.0.
 *
 * @param criteriaName the name of the dimension
 * @param description what the evaluator should look for, verbatim in the prompt
 * @param weight the importance of this criterion, 0.0 to 1.0
 * @param minimumScore the lowest acceptable score for this criterion
 */
public record EvaluationCriteria(
        String criteriaName,
        String description,
        double weight,
        double minimumScore
) {
    /**
     * Criteria for judging generated code: correctness 30%, readability 20%, efficiency 20%,
     * maintainability 20%, security 10%.
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
     * Criteria for judging generated prose: accuracy 35%, clarity 30%, completeness 20%,
     * engagement 15%.
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
