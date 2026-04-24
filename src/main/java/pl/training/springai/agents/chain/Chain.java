package pl.training.springai.agents.chain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.training.springai.agents.Action;
import pl.training.springai.agents.ActionResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Executor dla wzorca Prompt Chaining.
 *
 * Prompt Chaining (Lancuch Promptow):
 * - Dzieli zlozoone zadanie na sekwencje prostszych krokow
 * - Wyjscie jednego kroku staje sie wejsciem nastepnego
 * - Pozwala na walidacje i transformacje miedzy krokami
 *
 * Przebieg:
 * <pre>
 * Input -> [Krok 1] -> [Krok 2] -> ... -> [Krok N] -> Output
 * </pre>
 *
 * Przypadki uzycia:
 * - Code Review: Analiza -> Sugestie -> Refaktoryzacja
 * - Content Generation: Outline -> Draft -> Edit -> Final
 * - Data Processing: Extract -> Transform -> Validate -> Load
 *
 * Roznica od prostego pipe:
 * - Kazdy krok ma nazwe i opis (debugowanie)
 * - Mozliwosc trace'owania posrednich wynikow
 * - Opcja zatrzymania na bledzie
 *
 * Uzycie:
 * <pre>
 * var chain = Chain.builder()
 *     .addStep("analyze", "Znajdz problemy", analyzeAction)
 *     .addStep("improve", "Zaproponuj poprawki", improveAction)
 *     .addStep("refactor", "Wygeneruj kod", refactorAction)
 *     .build();
 * var result = chain.execute(sourceCode);
 * </pre>
 *
 * @param <I> Typ wejsciowy lancucha (pierwszy krok)
 * @param <O> Typ wyjsciowy lancucha (ostatni krok)
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
     * Wykonuje caly lancuch krokow.
     *
     * @param input Dane wejsciowe dla pierwszego kroku
     * @return Wynik ostatniego kroku
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
     * Wykonuje lancuch z pelnym sledzeniem wynikow posrednich.
     *
     * Przydatne do:
     * - Debugowania lancucha
     * - Analizy czasu wykonania kazdego kroku
     * - Identyfikacji kroku, ktory zawiodl
     *
     * @param input Dane wejsciowe
     * @return Lista wynikow kazdego kroku (ActionResult)
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
     * Tworzy builder dla lancucha.
     *
     * @param <I> Typ wejsciowy pierwszego kroku
     * @return Builder
     */
    public static <I> Builder<I, I> builder() {
        return new Builder<>();
    }

    /**
     * Builder dla lancucha akcji.
     *
     * Umozliwia deklaratywne budowanie lancucha z typowaniem.
     *
     * @param <I> Typ wejsciowy lancucha
     * @param <O> Aktualny typ wyjsciowy (zmienia sie z kazdym addStep)
     */
    public static class Builder<I, O> {
        private final List<ChainStep<?, ?>> steps = new ArrayList<>();
        private boolean stopOnError = true;

        /**
         * Dodaje krok do lancucha.
         *
         * @param name Nazwa kroku
         * @param description Opis kroku
         * @param action Akcja kroku
         * @param <R> Typ wyniku tego kroku
         * @return Builder z nowym typem wyjsciowym
         */
        @SuppressWarnings("unchecked")
        public <R> Builder<I, R> addStep(String name, String description, Action<O, R> action) {
            steps.add(new ChainStep<>(name, description, action));
            return (Builder<I, R>) this;
        }

        /**
         * Ustawia czy lancuch ma sie zatrzymac przy bledzie.
         *
         * @param stopOnError true = zatrzymaj na pierwszym bledzie
         * @return Builder
         */
        public Builder<I, O> stopOnError(boolean stopOnError) {
            this.stopOnError = stopOnError;
            return this;
        }

        /**
         * Buduje lancuch.
         *
         * @return Gotowy Chain do wykonania
         */
        public Chain<I, O> build() {
            return new Chain<>(new ArrayList<>(steps), stopOnError);
        }
    }
}
