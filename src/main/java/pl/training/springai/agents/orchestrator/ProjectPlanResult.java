package pl.training.springai.agents.orchestrator;

import java.util.List;

/**
 * Wynik wzorca Orchestrator-Workers.
 *
 * Orchestrator-Workers:
 * - Orchestrator (centralny LLM) analizuje zadanie i dzieli je na podzadania
 * - Workers (wywolania LLM) wykonuja podzadania
 * - Orchestrator integruje wyniki workerow w finalny rezultat
 *
 * Roznica od Parallelization:
 * - Parallelization: taski sa z gory zdefiniowane, niezalezne
 * - Orchestrator-Workers: taski sa dynamicznie tworzone przez LLM, moga zalezec od siebie
 *
 * Przebieg:
 * <pre>
 * Input -> [Orchestrator: Decompose] -> [Worker 1]
 *                                    -> [Worker 2]
 *                                    -> [Worker N]
 *       -> [Orchestrator: Integrate] -> Output
 * </pre>
 *
 * Przyklad uzycia:
 * <pre>
 * var result = orchestrator.execute("Build a REST API for todo items");
 *
 * System.out.println("Decomposed into " + result.decomposition().size() + " tasks:");
 * for (var task : result.decomposition()) {
 *     System.out.println(" - " + task.id() + ": " + task.description());
 * }
 *
 * System.out.println("Worker results:");
 * for (var worker : result.workerResults()) {
 *     System.out.println(" - " + worker.taskId() + ": " +
 *         (worker.success() ? "OK" : "FAILED"));
 * }
 *
 * System.out.println("Integrated result:\n" + result.integratedResult());
 * </pre>
 *
 * @param originalRequest Oryginalny request uzytkownika
 * @param decomposition Lista taskow utworzonych przez orchestratora
 * @param workerResults Wyniki poszczegolnych workerow
 * @param integratedResult Zintegrowany finalny wynik
 */
public record ProjectPlanResult(
        String originalRequest,
        List<WorkerTask> decomposition,
        List<WorkerResult> workerResults,
        String integratedResult
) {
    /**
     * Wynik pojedynczego workera.
     *
     * @param taskId ID tasku ktory worker wykonal
     * @param output Wynik pracy workera (kod, tekst, etc.)
     * @param success Czy worker zakonczyl pomyslnie
     * @param executionTimeMs Czas wykonania w ms
     */
    public record WorkerResult(
            String taskId,
            String output,
            boolean success,
            long executionTimeMs
    ) {}
}
