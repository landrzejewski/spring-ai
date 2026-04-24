package pl.training.springai.agents.parallelization;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.training.springai.agents.Action;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * Executor dla wzorca Parallelization.
 *
 * Parallelization (Zrownoleglenie):
 * - Dzieli zadanie na niezalezne podzadania
 * - Wykonuje je rownolegle (kazde w osobnym watku/wywolaniu LLM)
 * - Agreguje wyniki w finalny rezultat
 *
 * Przebieg:
 * <pre>
 * Input -> [Task A] -\
 *       -> [Task B] ---> [Aggregator] -> Output
 *       -> [Task C] -/
 * </pre>
 *
 * Dlaczego to dziala:
 * - Wywolania LLM sa I/O-bound (czekamy na API)
 * - Rownoleglosc skraca czas z N * T do max(T1, T2, ..., TN)
 * - Niezalezne aspekty nie wymagaja sekwencyjnosci
 *
 * Virtual Threads (Java 21+):
 * - Uzywa Executors.newVirtualThreadPerTaskExecutor()
 * - Lekkie watki, nie blokuja platformowych
 * - Idealne dla I/O-bound operations jak wywolania API
 *
 * Uzycie:
 * <pre>
 * var parallelizer = ParallelizerAction.&lt;Request, Result&gt;builder(results -> {
 *         var quality = (QualityResult) results.get(0);
 *         var security = (SecurityResult) results.get(1);
 *         return new Result(quality, security);
 *     })
 *     .addTask("quality", qualityAction)
 *     .addTask("security", securityAction)
 *     .build();
 *
 * var result = parallelizer.execute(request);
 * </pre>
 *
 * @param <I> Typ wejsciowy (wspolny dla wszystkich taskow)
 * @param <O> Typ wyjsciowy (zagregowany wynik)
 */
public class ParallelizerAction<I, O> implements Action<I, O> {

    private static final Logger LOGGER = LoggerFactory.getLogger(ParallelizerAction.class);

    private final List<ParallelTask<I, ?>> tasks;
    private final Function<List<Object>, O> aggregator;
    private final ExecutorService executor;

    private ParallelizerAction(List<ParallelTask<I, ?>> tasks,
                               Function<List<Object>, O> aggregator,
                               ExecutorService executor) {
        this.tasks = tasks;
        this.aggregator = aggregator;
        this.executor = executor;
    }

    @Override
    public O execute(I input) {
        long startTime = System.currentTimeMillis();
        LOGGER.info("Starting parallel execution of {} tasks", tasks.size());

        // 1. Uruchom wszystkie taski rownolegle
        List<CompletableFuture<Object>> futures = tasks.stream()
                .map(task -> CompletableFuture.supplyAsync(
                        () -> {
                            LOGGER.info("Starting task: {}", task.name());
                            long taskStart = System.currentTimeMillis();
                            var result = task.action().execute(input);
                            LOGGER.info("Task {} completed in {}ms", task.name(),
                                    System.currentTimeMillis() - taskStart);
                            return result;
                        },
                        executor
                ))
                .map(f -> f.thenApply(r -> (Object) r))
                .toList();

        // 2. Poczekaj na wszystkie wyniki
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // 3. Zbierz wyniki (w kolejnosci dodania taskow)
        List<Object> results = futures.stream()
                .map(CompletableFuture::join)
                .toList();

        // 4. Agreguj wyniki
        long totalTime = System.currentTimeMillis() - startTime;
        LOGGER.info("All tasks completed in {}ms (parallel)", totalTime);

        return aggregator.apply(results);
    }

    /**
     * Reprezentuje pojedynczy task do rownoleglego wykonania.
     *
     * @param name Nazwa tasku (do logowania)
     * @param action Akcja do wykonania
     */
    private record ParallelTask<I, O>(
            String name,
            Action<I, O> action
    ) {}

    /**
     * Tworzy builder dla parallelizera.
     *
     * @param aggregator Funkcja agregujaca wyniki taskow
     * @param <I> Typ wejsciowy
     * @param <O> Typ wyjsciowy
     * @return Builder
     */
    public static <I, O> Builder<I, O> builder(Function<List<Object>, O> aggregator) {
        return new Builder<>(aggregator);
    }

    /**
     * Builder dla parallelizera.
     *
     * @param <I> Typ wejsciowy
     * @param <O> Typ wyjsciowy
     */
    public static class Builder<I, O> {
        private final List<ParallelTask<I, ?>> tasks = new ArrayList<>();
        private final Function<List<Object>, O> aggregator;
        private ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        public Builder(Function<List<Object>, O> aggregator) {
            this.aggregator = aggregator;
        }

        /**
         * Dodaje task do rownoleglego wykonania.
         *
         * Kolejnosc dodawania ma znaczenie - wyniki beda w tej samej kolejnosci
         * w liscie przekazanej do agregatora.
         *
         * @param name Nazwa tasku
         * @param action Akcja tasku
         * @param <R> Typ wyniku tasku
         * @return Builder
         */
        public <R> Builder<I, O> addTask(String name, Action<I, R> action) {
            tasks.add(new ParallelTask<>(name, action));
            return this;
        }

        /**
         * Ustawia wlasny executor (domyslnie Virtual Threads).
         *
         * @param executor ExecutorService do uzycia
         * @return Builder
         */
        public Builder<I, O> executor(ExecutorService executor) {
            this.executor = executor;
            return this;
        }

        /**
         * Buduje parallelizer.
         *
         * @return Gotowy ParallelizerAction
         */
        public ParallelizerAction<I, O> build() {
            return new ParallelizerAction<>(new ArrayList<>(tasks), aggregator, executor);
        }
    }
}
