package pl.training.agents;

/**
 * The outcome of one Action, with the timing and the failure reason kept alongside the value.
 * <p>
 * Model calls fail in ways ordinary code does not - a rate limit, a refusal, output that will not
 * parse - and in a multi-step workflow one failed step should not lose the results of the steps
 * that succeeded. Recording the outcome per step is what makes a chain debuggable.
 *
 * @param success whether the action completed
 * @param result the value produced, null when success is false
 * @param error the failure message, null when success is true
 * @param executionTimeMs wall-clock duration, useful because model calls dominate it
 * @param <T> the result type
 */
public record ActionResult<T>(
        boolean success,
        T result,
        String error,
        long executionTimeMs
) {
    /**
     * @param result the value produced
     * @param executionTimeMs duration in milliseconds
     * @param <T> the result type
     * @return a successful ActionResult
     */
    public static <T> ActionResult<T> success(T result, long executionTimeMs) {
        return new ActionResult<>(true, result, null, executionTimeMs);
    }

    /**
     * @param error the failure message
     * @param executionTimeMs duration in milliseconds
     * @param <T> the result type, always null here
     * @return a failed ActionResult
     */
    public static <T> ActionResult<T> failure(String error, long executionTimeMs) {
        return new ActionResult<>(false, null, error, executionTimeMs);
    }
}
