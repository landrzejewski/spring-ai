package pl.training.agents.chain;

import pl.training.agents.Action;

/**
 * One named step of a Chain: its own prompt, its own structured output type, its own place in the
 * trace. The name and description exist for debugging - they never reach the model.
 *
 * @param name step name, used in logs and traces
 * @param description what the step does
 * @param action the work itself, usually a single ChatClient call
 * @param <I> the step input type
 * @param <O> the step output type
 */
public record ChainStep<I, O>(
        String name,
        String description,
        Action<I, O> action
) {
    /**
     * @param input the data to process
     * @return the result, which becomes the next step's input
     */
    public O execute(I input) {
        return action.execute(input);
    }
}
