package pl.training.agents.chain;

import java.util.List;

/**
 * The combined output of the three code-review chain steps.
 * <p>
 * Keeping the intermediate results next to the final code is deliberate: the refactored code alone
 * is unreviewable, while the findings and suggestions that produced it can be checked.
 *
 * @param originalCode the source that was reviewed
 * @param analysisFindings the problems found in step 1
 * @param improvementSuggestions the fixes proposed in step 2
 * @param refactoredCode the code produced in step 3
 * @param summary a short description of what changed
 */
public record CodeReviewChainResult(
        String originalCode,
        List<String> analysisFindings,
        List<String> improvementSuggestions,
        String refactoredCode,
        String summary
) {}
