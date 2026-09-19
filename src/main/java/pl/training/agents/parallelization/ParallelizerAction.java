package pl.training.agents.parallelization;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.training.agents.Action;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * Runs independent Actions on the same input concurrently and merges their results.
 * <pre>
 * Input -> [Task A] -\
 *       -> [Task B] ---> [Aggregator] -> Output
 *       -> [Task C] -/
 * </pre>
 * A model call spends nearly all its time waiting on the provider, so running N of them
 * concurrently turns N * T into max(T1..TN) - a code review that took thirty seconds sequentially
 * finishes in the time of its slowest analysis.
 * <p>
 * The precondition is genuine independence: no task may need another's output. When they do, use
 * Chain instead.
 *
 * @param <I> the input type, shared by every task
 * @param <O> the aggregated output type
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

        // 1. Submit every task; each one is a separate model call
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

        // 2. Wait for all of them
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // 3. Collect the results, in the order the tasks were registered
        List<Object> results = futures.stream()
                .map(CompletableFuture::join)
                .toList();

        // 4. Merge them
        long totalTime = System.currentTimeMillis() - startTime;
        LOGGER.info("All tasks completed in {}ms (parallel)", totalTime);

        return aggregator.apply(results);
    }

    /**
     * @param name task name, used in logs
     * @param action the work itself
     */
    private record ParallelTask<I, O>(
            String name,
            Action<I, O> action
    ) {}

    /**
     * @param aggregator merges the task results into the final output
     * @param <I> the input type
     * @param <O> the output type
     * @return a builder
     */
    public static <I, O> Builder<I, O> builder(Function<List<Object>, O> aggregator) {
        return new Builder<>(aggregator);
    }

    /**
     * @param <I> the input type
     * @param <O> the output type
     */
    public static class Builder<I, O> {
        private final List<ParallelTask<I, ?>> tasks = new ArrayList<>();
        private final Function<List<Object>, O> aggregator;
        private ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        public Builder(Function<List<Object>, O> aggregator) {
            this.aggregator = aggregator;
        }

        /**
         * Registration order matters: the aggregator receives the results in the order the tasks
         * were added, not in the order they completed.
         *
         * @param name task name
         * @param action the work itself
         * @param <R> the task result type
         * @return the builder
         */
        public <R> Builder<I, O> addTask(String name, Action<I, R> action) {
            tasks.add(new ParallelTask<>(name, action));
            return this;
        }

        /**
         * @param executor the executor to run the tasks on
         * @return the builder
         */
        public Builder<I, O> executor(ExecutorService executor) {
            this.executor = executor;
            return this;
        }

        /**
         * @return the assembled parallelizer
         */
        public ParallelizerAction<I, O> build() {
            return new ParallelizerAction<>(new ArrayList<>(tasks), aggregator, executor);
        }
    }
}
