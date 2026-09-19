package pl.training.agents.chain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.training.agents.Action;
import pl.training.agents.ActionResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs a sequence of Actions, feeding each result into the next step.
 * <pre>
 * Input -> [step 1] -> [step 2] -> ... -> [step N] -> Output
 * </pre>
 * Splitting one large prompt into several narrow ones is the whole point: each step gets
 * instructions for a single job and its own structured output type, which is far more reliable
 * than asking a model to analyse, decide and rewrite in one response.
 * <p>
 * Beyond plain function composition this adds a name and description per step and, through
 * {@link #executeWithTrace}, the intermediate results - without which a chain that produced a poor
 * final answer gives no clue about which step went wrong.
 *
 * @param <I> the input type of the first step
 * @param <O> the output type of the last step
 */
public class Chain<I, O> implements Action<I, O> {

    private static final Logger LOGGER = LoggerFactory.getLogger(Chain.class);

    private final List<ChainStep<?, ?>> steps;
    private final boolean stopOnError;

    private Chain(List<ChainStep<?, ?>> steps, boolean stopOnError) {
        this.steps = steps;
        this.stopOnError = stopOnError;
    }

    /**
     * Runs every step and returns the last result.
     *
     * @param input the input of the first step
     * @return the output of the last step
     */
    @Override
    @SuppressWarnings("unchecked")
    public O execute(I input) {
        Object currentResult = input;
        for (ChainStep<?, ?> step : steps) {
            LOGGER.info("Executing chain step: {} - {}", step.name(), step.description());
            ChainStep<Object, Object> typedStep = (ChainStep<Object, Object>) step;
            currentResult = typedStep.execute(currentResult);
        }
        return (O) currentResult;
    }

    /**
     * Runs the chain and keeps every intermediate result, so a disappointing final answer can be
     * traced back to the step that caused it - and the per-step timings show where the model calls
     * actually go.
     *
     * @param input the input of the first step
     * @return one ActionResult per step
     */
    @SuppressWarnings("unchecked")
    public List<ActionResult<?>> executeWithTrace(I input) {
        List<ActionResult<?>> trace = new ArrayList<>();
        Object currentResult = input;

        for (ChainStep<?, ?> step : steps) {
            long startTime = System.currentTimeMillis();
            try {
                LOGGER.info("Executing chain step: {} - {}", step.name(), step.description());
                ChainStep<Object, Object> typedStep = (ChainStep<Object, Object>) step;
                currentResult = typedStep.execute(currentResult);
                long executionTime = System.currentTimeMillis() - startTime;
                trace.add(ActionResult.success(currentResult, executionTime));
                LOGGER.info("Step {} completed in {}ms", step.name(), executionTime);
            } catch (Exception e) {
                long executionTime = System.currentTimeMillis() - startTime;
                trace.add(ActionResult.failure(e.getMessage(), executionTime));
                LOGGER.error("Step {} failed: {}", step.name(), e.getMessage());
                if (stopOnError) {
                    break;
                }
            }
        }

        return trace;
    }

    /**
     * @param <I> the input type of the first step
     * @return a builder
     */
    public static <I> Builder<I, I> builder() {
        return new Builder<>();
    }

    /**
     * Builds the chain step by step, carrying the types along: each addStep call changes the
     * builder's output type, so a step whose input does not match the previous step's output will
     * not compile.
     *
     * @param <I> the chain input type
     * @param <O> the current output type
     */
    public static class Builder<I, O> {
        private final List<ChainStep<?, ?>> steps = new ArrayList<>();
        private boolean stopOnError = true;

        /**
         * @param name step name, used in the trace
         * @param description what the step does
         * @param action the work itself
         * @param <R> the result type of this step
         * @return the builder, retyped to this step's output
         */
        @SuppressWarnings("unchecked")
        public <R> Builder<I, R> addStep(String name, String description, Action<O, R> action) {
            steps.add(new ChainStep<>(name, description, action));
            return (Builder<I, R>) this;
        }

        /**
         * @param stopOnError true to abort on the first failed step
         * @return the builder
         */
        public Builder<I, O> stopOnError(boolean stopOnError) {
            this.stopOnError = stopOnError;
            return this;
        }

        /**
         * @return the assembled chain
         */
        public Chain<I, O> build() {
            return new Chain<>(new ArrayList<>(steps), stopOnError);
        }
    }
}
