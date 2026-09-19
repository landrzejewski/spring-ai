package pl.training.agents.orchestrator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import pl.training.agents.Action;

import java.util.*;

/**
 * A central model call decomposes the request into tasks, workers execute them in dependency
 * order, and a final call integrates the results.
 * <p>
 * What distinguishes this from the other patterns is where the plan comes from. Chain has a fixed
 * sequence and ParallelizerAction a fixed task list; here the task list, their dependencies and
 * their priorities are structured output from the model, so the same code handles requests whose
 * shape nobody anticipated.
 * <p>
 * That flexibility is also the risk: the model can produce an unworkable plan, and nothing
 * downstream will notice. maxWorkers bounds the damage.
 */
public class Orchestrator implements Action<String, ProjectPlanResult> {

    private static final Logger LOGGER = LoggerFactory.getLogger(Orchestrator.class);

    private final ChatClient chatClient;
    private final Action<WorkerTask, String> workerAction;
    private final int maxWorkers;

    private Orchestrator(ChatClient chatClient,
                         Action<WorkerTask, String> workerAction,
                         int maxWorkers) {
        this.chatClient = chatClient;
        this.workerAction = workerAction;
        this.maxWorkers = maxWorkers;
    }

    @Override
    public ProjectPlanResult execute(String request) {
        LOGGER.info("Orchestrator received request: {}", request.substring(0, Math.min(50, request.length())));

        // 1. Decompose: one model call produces the task list
        LOGGER.info("Decomposing task into subtasks...");
        var decomposition = decompose(request);
        LOGGER.info("Created {} subtasks", decomposition.size());

        // 2. Execute: one model call per task, in dependency order
        LOGGER.info("Executing workers...");
        var workerResults = executeWorkers(decomposition);
        LOGGER.info("Workers completed: {} successful, {} failed",
                workerResults.stream().filter(ProjectPlanResult.WorkerResult::success).count(),
                workerResults.stream().filter(r -> !r.success()).count());

        // 3. Integrate: a final call merges the worker outputs
        LOGGER.info("Integrating results...");
        var integrated = integrate(request, workerResults);

        return new ProjectPlanResult(request, decomposition, workerResults, integrated);
    }

    /**
     * The decomposition call. The plan comes back as structured output - a list of WorkerTask with
     * ids, priorities and dependencies - which is what makes it executable rather than advisory.
     */
    private List<WorkerTask> decompose(String request) {
        var result = chatClient.prompt()
                .system("""
                        You are a project planning expert and software architect.

                        Analyze the given task and break it down into smaller, manageable subtasks.
                        For each subtask, provide:
                        - id: unique identifier (task-1, task-2, etc.)
                        - description: clear description of what needs to be done
                        - context: any relevant context, requirements, or specifications
                        - priority: 1 (highest/do first) to 5 (lowest/can wait)
                        - dependencies: list of task IDs this task depends on (empty list if none)

                        Important:
                        - Create at most %d subtasks
                        - Focus on the most important work first
                        - Ensure dependencies form a valid DAG (no cycles)
                        - Make tasks specific and actionable
                        """.formatted(maxWorkers))
                .user(request)
                .call()
                .entity(TaskDecomposition.class);

        return result.tasks() != null ? result.tasks() : List.of();
    }

    /**
     * Runs the workers in dependency order: repeated passes over the task list, executing whatever
     * has all its dependencies satisfied. A task whose dependencies never complete is simply never
     * run, which keeps a cyclic or impossible plan from hanging the request.
     */
    private List<ProjectPlanResult.WorkerResult> executeWorkers(List<WorkerTask> tasks) {
        List<ProjectPlanResult.WorkerResult> results = new ArrayList<>();
        Set<String> completed = new HashSet<>();

        // Priority 1 is the highest
        var sortedTasks = new ArrayList<>(tasks);
        sortedTasks.sort(Comparator.comparingInt(WorkerTask::priority));

        // Repeated passes, so a task becomes runnable once its dependencies finish
        int maxPasses = sortedTasks.size();
        for (int pass = 0; pass < maxPasses; pass++) {
            for (WorkerTask task : sortedTasks) {
                // Already done
                if (completed.contains(task.id())) {
                    continue;
                }

                // Runnable only when every dependency has completed
                boolean dependenciesMet = task.dependencies() == null ||
                        completed.containsAll(task.dependencies());

                if (!dependenciesMet) {
                    continue; // Pomin - zaleznosci nieukonczone
                }

                // Run it
                LOGGER.info("Executing worker for task: {} - {}", task.id(), task.description());
                long startTime = System.currentTimeMillis();
                try {
                    String output = workerAction.execute(task);
                    long executionTime = System.currentTimeMillis() - startTime;
                    results.add(new ProjectPlanResult.WorkerResult(
                            task.id(), output, true, executionTime
                    ));
                    completed.add(task.id());
                    LOGGER.info("Task {} completed in {}ms", task.id(), executionTime);
                } catch (Exception e) {
                    long executionTime = System.currentTimeMillis() - startTime;
                    results.add(new ProjectPlanResult.WorkerResult(
                            task.id(), "Error: " + e.getMessage(), false, executionTime
                    ));
                    LOGGER.error("Task {} failed: {}", task.id(), e.getMessage());
                }
            }
        }

        return results;
    }

    /**
     * The integration call. The worker outputs were produced independently, so something has to
     * reconcile them - naming, imports, overlaps - which is a job for the model, not string
     * concatenation.
     */
    private String integrate(String originalRequest,
                             List<ProjectPlanResult.WorkerResult> workerResults) {
        var resultsText = workerResults.stream()
                .filter(ProjectPlanResult.WorkerResult::success)
                .map(r -> "=== Task %s ===\n%s".formatted(r.taskId(), r.output()))
                .reduce("", (a, b) -> a + "\n\n" + b);

        return chatClient.prompt()
                .system("""
                        You are a project integration expert.

                        The original request was processed by multiple workers.
                        Your task is to integrate their outputs into a coherent final result.

                        Ensure:
                        - All parts work together seamlessly
                        - No conflicts or inconsistencies
                        - The result fully addresses the original request
                        - Code is properly formatted and organized
                        - Any missing pieces are noted
                        """)
                .user("""
                        Original request: %s

                        Worker outputs:
                        %s

                        Please integrate these into a final coherent result.
                        If this is code, ensure it compiles and follows best practices.
                        """.formatted(originalRequest, resultsText))
                .call()
                .content();
    }

    /**
     * The structured output type of the decomposition call.
     */
    private record TaskDecomposition(List<WorkerTask> tasks) {}

    /**
     * @param chatClient the client used for decomposition and integration
     * @return a builder
     */
    public static Builder builder(ChatClient chatClient) {
        return new Builder(chatClient);
    }

    /**
     * Assembles the orchestrator.
     */
    public static class Builder {
        private final ChatClient chatClient;
        private Action<WorkerTask, String> workerAction;
        private int maxWorkers = 5;

        public Builder(ChatClient chatClient) {
            this.chatClient = chatClient;
        }

        /**
         * @param action how a worker executes one task; defaults to a plain model call
         * @return the builder
         */
        public Builder workerAction(Action<WorkerTask, String> action) {
            this.workerAction = action;
            return this;
        }

        /**
         * Caps how many tasks the plan may contain - each one is a model call, so an
         * over-enthusiastic decomposition is a cost problem.
         *
         * @param max the maximum number of tasks
         * @return the builder
         */
        public Builder maxWorkers(int max) {
            this.maxWorkers = max;
            return this;
        }

        /**
         * @return the assembled orchestrator
         */
        public Orchestrator build() {
            if (workerAction == null) {
                // Default worker: a single model call per task
                workerAction = task -> chatClient.prompt()
                        .system("""
                                You are a skilled software developer.
                                Execute the given task thoroughly and provide complete output.
                                If generating code, make it production-ready with proper error handling.
                                """)
                        .user("Task: " + task.description() + "\n\nContext: " + task.context())
                        .call()
                        .content();
            }
            return new Orchestrator(chatClient, workerAction, maxWorkers);
        }
    }
}
