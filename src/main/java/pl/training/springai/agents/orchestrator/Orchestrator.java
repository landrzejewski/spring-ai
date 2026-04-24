package pl.training.springai.agents.orchestrator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import pl.training.springai.agents.Action;

import java.util.*;

/**
 * Implementacja wzorca Orchestrator-Workers.
 *
 * Orchestrator-Workers:
 * Wzorzec, w ktorym centralny LLM (orchestrator) dynamicznie dzieli zadanie
 * na podzadania i deleguje je do workerow.
 *
 * Przebieg:
 * 1. Uzytkownik wysyla zlozoone zadanie (np. "Zbuduj REST API")
 * 2. Orchestrator analizuje i tworzy plan (lista WorkerTask)
 * 3. Workers wykonuja podzadania (respektujac zaleznosci)
 * 4. Orchestrator integruje wyniki w finalny rezultat
 *
 * Roznica od innych wzorcow:
 * - Chain: stala sekwencja, brak dynamicznej dekompozycji
 * - Parallel: stale taski, brak zaleznosci
 * - Orchestrator: dynamiczna dekompozycja, zaleznosci, integracja
 *
 * Przypadki uzycia:
 * - Project Planning: Rozbij projekt na komponenty
 * - Code Refactoring: Podziel refaktoring na kroki
 * - Document Generation: Sekcje dokumentu przez specjalistow
 * - Complex Analysis: Rozne aspekty analizowane osobno
 *
 * Uzycie:
 * <pre>
 * var orchestrator = Orchestrator.builder(chatClient)
 *     .workerAction(workerAction)
 *     .maxWorkers(5)
 *     .build();
 *
 * var result = orchestrator.execute("Build a REST API for a todo app");
 * // result.decomposition() - lista taskow
 * // result.workerResults() - wyniki workerow
 * // result.integratedResult() - finalny kod/wynik
 * </pre>
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

        // 1. Orchestrator dekomponuje zadanie
        LOGGER.info("Decomposing task into subtasks...");
        var decomposition = decompose(request);
        LOGGER.info("Created {} subtasks", decomposition.size());

        // 2. Workers wykonuja podzadania (w kolejnosci zaleznosci)
        LOGGER.info("Executing workers...");
        var workerResults = executeWorkers(decomposition);
        LOGGER.info("Workers completed: {} successful, {} failed",
                workerResults.stream().filter(ProjectPlanResult.WorkerResult::success).count(),
                workerResults.stream().filter(r -> !r.success()).count());

        // 3. Orchestrator integruje wyniki
        LOGGER.info("Integrating results...");
        var integrated = integrate(request, workerResults);

        return new ProjectPlanResult(request, decomposition, workerResults, integrated);
    }

    /**
     * Orchestrator dekomponuje zadanie na podzadania uzywajac LLM.
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
     * Wykonuje workerow respektujac zaleznosci.
     *
     * Algorytm:
     * 1. Sortuj taski po priorytecie
     * 2. Dla kazdego tasku sprawdz czy wszystkie zaleznosci sa ukonczone
     * 3. Jesli tak - wykonaj worker
     * 4. Jesli nie - pomin (moze byc wykonany w nastepnej iteracji)
     */
    private List<ProjectPlanResult.WorkerResult> executeWorkers(List<WorkerTask> tasks) {
        List<ProjectPlanResult.WorkerResult> results = new ArrayList<>();
        Set<String> completed = new HashSet<>();

        // Sortuj po priorytecie (1 = najwyzszy)
        var sortedTasks = new ArrayList<>(tasks);
        sortedTasks.sort(Comparator.comparingInt(WorkerTask::priority));

        // Wielokrotne przejscia aby obsluzyc zaleznosci
        int maxPasses = sortedTasks.size();
        for (int pass = 0; pass < maxPasses; pass++) {
            for (WorkerTask task : sortedTasks) {
                // Pomin jesli juz wykonany
                if (completed.contains(task.id())) {
                    continue;
                }

                // Sprawdz czy wszystkie zaleznosci sa ukonczone
                boolean dependenciesMet = task.dependencies() == null ||
                        completed.containsAll(task.dependencies());

                if (!dependenciesMet) {
                    continue; // Pomin - zaleznosci nieukonczone
                }

                // Wykonaj worker
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
     * Orchestrator integruje wyniki workerow w finalny rezultat.
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
     * Wewnetrzny record do dekompozycji przez LLM.
     */
    private record TaskDecomposition(List<WorkerTask> tasks) {}

    /**
     * Tworzy builder dla orchestratora.
     *
     * @param chatClient ChatClient do uzycia
     * @return Builder
     */
    public static Builder builder(ChatClient chatClient) {
        return new Builder(chatClient);
    }

    /**
     * Builder dla orchestratora.
     */
    public static class Builder {
        private final ChatClient chatClient;
        private Action<WorkerTask, String> workerAction;
        private int maxWorkers = 5;

        public Builder(ChatClient chatClient) {
            this.chatClient = chatClient;
        }

        /**
         * Ustawia akcje workera (jak worker wykonuje taski).
         *
         * @param action Akcja workera
         * @return Builder
         */
        public Builder workerAction(Action<WorkerTask, String> action) {
            this.workerAction = action;
            return this;
        }

        /**
         * Ustawia maksymalna liczbe workerow/taskow.
         *
         * @param max Maksymalna liczba taskow
         * @return Builder
         */
        public Builder maxWorkers(int max) {
            this.maxWorkers = max;
            return this;
        }

        /**
         * Buduje orchestratora.
         *
         * @return Gotowy Orchestrator
         */
        public Orchestrator build() {
            if (workerAction == null) {
                // Default worker - proste wykonanie przez LLM
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
