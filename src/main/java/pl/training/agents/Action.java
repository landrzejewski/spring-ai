package pl.training.agents;

import java.util.function.Function;

/**
 * One unit of work in an agentic workflow - typically a single ChatClient call that returns
 * structured output.
 * <p>
 * Because the step is a typed function, the patterns in this package are ordinary composition:
 * Chain runs actions in sequence, ParallelizerAction runs them concurrently, Orchestrator hands
 * them to workers. Nothing here is Spring AI machinery; the framework's contribution is that
 * {@code entity()} makes a model call return a value that the next action can accept as input.
 *
 * @param <I> input type
 * @param <O> output type
 */
@FunctionalInterface
public interface Action<I, O> extends Function<I, O> {

    /**
     * Runs the action.
     *
     * @param input the data to process
     * @return the result
     */
    O execute(I input);

    @Override
    default O apply(I input) {
        return execute(input);
    }

    /**
     * Composes two actions: this action's output becomes the next one's input. This is prompt
     * chaining in its smallest form.
     *
     * @param next the following action
     * @param <R> the result type of the following action
     * @return the combined action
     */
    default <R> Action<I, R> then(Action<O, R> next) {
        return input -> next.execute(this.execute(input));
    }
}
