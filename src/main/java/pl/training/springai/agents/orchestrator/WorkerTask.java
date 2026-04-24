package pl.training.springai.agents.orchestrator;

import java.util.List;

/**
 * Zadanie przydzielone workerowi przez orchestratora.
 *
 * Orchestrator-Workers to wzorzec, w ktorym centralny LLM (orchestrator)
 * dynamicznie dzieli zadanie na podzadania i deleguje je do workerow.
 *
 * WorkerTask reprezentuje pojedyncze podzadanie:
 * - Ma unikalny identyfikator (do sledzenia)
 * - Opis co worker ma zrobic
 * - Kontekst potrzebny do wykonania
 * - Priorytet (wplywana kolejnosc)
 * - Zaleznosci (ktore taski musza sie skonczyc wczesniej)
 *
 * Przyklad dekompozycji "Zbuduj REST API dla todo app":
 * <pre>
 * WorkerTask task1 = new WorkerTask("task-1", "Create Todo model class",
 *     "Fields: id, title, completed, createdAt", 1, List.of());
 *
 * WorkerTask task2 = new WorkerTask("task-2", "Create TodoRepository",
 *     "JPA repository for Todo entity", 2, List.of("task-1"));
 *
 * WorkerTask task3 = new WorkerTask("task-3", "Create TodoService",
 *     "Business logic for CRUD operations", 2, List.of("task-2"));
 * </pre>
 *
 * @param id Unikalny identyfikator zadania (np. "task-1", "task-2")
 * @param description Opis co worker ma zrobic
 * @param context Kontekst potrzebny do wykonania (np. wymagania, specyfikacja)
 * @param priority Priorytet od 1 (najwyzszy) do 5 (najnizszy)
 * @param dependencies Lista ID zadan od ktorych to zadanie zalezy (musza sie skonczyc wczesniej)
 */
public record WorkerTask(
        String id,
        String description,
        String context,
        int priority,
        List<String> dependencies
) {}
