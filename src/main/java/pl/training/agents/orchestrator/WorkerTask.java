package pl.training.agents.orchestrator;

import java.util.List;

/**
 * One task in the plan the orchestrator produced.
 * <p>
 * This is a structured output type, so the field names and their descriptions are what the model
 * is asked to fill in. The dependency list is the important one: it turns a flat list of ideas
 * into an executable order, and getting the model to state dependencies explicitly is what makes
 * the pattern work at all.
 * <p>
 * Decomposing "Build a REST API for a todo app" typically yields the Todo model with no
 * dependencies, the repository depending on the model, the service depending on the repository.
 *
 * @param id a unique identifier, referenced by other tasks' dependencies
 * @param description what the worker should do
 * @param context requirements or specification the worker needs
 * @param priority 1 (highest) to 5 (lowest)
 * @param dependencies ids of tasks that must complete first
 */
public record WorkerTask(
        String id,
        String description,
        String context,
        int priority,
        List<String> dependencies
) {}
