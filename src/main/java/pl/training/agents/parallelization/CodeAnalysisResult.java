package pl.training.agents.parallelization;

import java.util.List;

/**
 * The merged output of the three parallel analyses.
 * <p>
 * Each nested record is the structured output type of one model call. Splitting the analysis this
 * way is not only about speed: a prompt asked for quality, security and performance at once tends
 * to produce a shallow pass over all three, while a prompt asked for security alone digs.
 *
 * @param qualityAnalysis readability, code smells, structure
 * @param securityAnalysis vulnerabilities and data exposure
 * @param performanceAnalysis complexity and bottlenecks
 * @param overallScore weighted average: 40% quality, 35% security, 25% performance
 * @param executionTimeMs wall-clock time, which is the slowest call rather than the sum
 */
public record CodeAnalysisResult(
        QualityAnalysis qualityAnalysis,
        SecurityAnalysis securityAnalysis,
        PerformanceAnalysis performanceAnalysis,
        double overallScore,
        long executionTimeMs
) {
    /**
     * Code smells, readability, documentation, adherence to conventions.
     *
     * @param score 0.0 (poor) to 1.0 (excellent)
     * @param issues the problems found
     * @param suggestions how to fix them
     */
    public record QualityAnalysis(
            double score,
            List<String> issues,
            List<String> suggestions
    ) {}

    /**
     * Injection, XSS, broken authorization, data leaks, hardcoded secrets.
     *
     * @param score 0.0 (critical vulnerabilities) to 1.0 (clean)
     * @param vulnerabilities what was found
     * @param severity the highest severity present: LOW, MEDIUM, HIGH or CRITICAL
     */
    public record SecurityAnalysis(
            double score,
            List<String> vulnerabilities,
            String severity
    ) {}

    /**
     * Time complexity, memory use, I/O bottlenecks, caching opportunities, N+1 queries.
     *
     * @param score 0.0 (very slow) to 1.0 (optimal)
     * @param bottlenecks what was identified
     * @param optimizations proposed improvements
     */
    public record PerformanceAnalysis(
            double score,
            List<String> bottlenecks,
            List<String> optimizations
    ) {}
}
