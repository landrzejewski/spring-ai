package pl.training.agents.orchestrator;

import java.util.List;

/**
 * The full record of an orchestrated run.
 * <pre>
 * Input -> [decompose] -> [worker 1] [worker 2] ... [worker N] -> [integrate] -> Output
 * </pre>
 * The plan is returned alongside the answer because it is the part worth inspecting: when the
 * result disappoints, the decomposition usually shows why, and it is the step no one wrote.
 *
 * @param originalRequest what was asked for
 * @param decomposition the tasks the model produced
 * @param workerResults what each worker returned
 * @param integratedResult the merged final answer
 */
public record ProjectPlanResult(
        String originalRequest,
        List<WorkerTask> decomposition,
        List<WorkerResult> workerResults,
        String integratedResult
) {
    /**
     * @param taskId the task this worker executed
     * @param output what it produced
     * @param success whether it completed
     * @param executionTimeMs duration in milliseconds
     */
    public record WorkerResult(
            String taskId,
            String output,
            boolean success,
            long executionTimeMs
    ) {}
}
