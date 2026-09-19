package pl.training.agents.parallelization;

/**
 * The shared input of the three parallel analyses.
 * <p>
 * Every parallel task receives the same request; only the prompt differs. Language and context are
 * carried explicitly because a model told what the code is for reports different problems than one
 * shown the code alone.
 *
 * @param sourceCode the code to analyse
 * @param language the programming language
 * @param context what the code is for
 */
public record CodeAnalysisRequest(
        String sourceCode,
        String language,
        String context
) {}
