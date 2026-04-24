package pl.training.springai.agents;

import java.util.function.Function;

/**
 * Interfejs funkcyjny reprezentujacy pojedyncza akcje w agencie.
 *
 * Akcja to jednostka pracy, ktora przyjmuje wejscie i zwraca wynik.
 * Akcje moga byc laczone w lancuchy (Chain), wykonywane rownolegle (Parallelizer),
 * lub delegowane do workerow (Orchestrator).
 *
 * Wzorzec projektowy:
 * - Implementuje Function<I, O> dla kompatybilnosci z Java Streams
 * - Umozliwia kompozycje akcji przez andThen() i compose()
 *
 * Przyklad uzycia:
 * <pre>
 * Action<String, AnalysisResult> analyzeCode = code -> chatClient.prompt()...
 * Action<AnalysisResult, String> generateReport = result -> chatClient.prompt()...
 * Action<String, String> pipeline = analyzeCode.then(generateReport);
 * </pre>
 *
 * @param <I> Typ wejsciowy akcji
 * @param <O> Typ wyjsciowy akcji
 */
@FunctionalInterface
public interface Action<I, O> extends Function<I, O> {

    /**
     * Wykonuje akcje na podanym wejsciu.
     *
     * @param input Dane wejsciowe do przetworzenia
     * @return Wynik akcji
     */
    O execute(I input);

    @Override
    default O apply(I input) {
        return execute(input);
    }

    /**
     * Tworzy nowa akcje bedaca polaczeniem tej akcji z kolejna.
     * Wynik tej akcji staje sie wejsciem dla nastepnej (Prompt Chaining).
     *
     * Przebieg: this.execute(input) -> next.execute(result)
     *
     * @param next Nastepna akcja w lancuchu
     * @param <R> Typ wyniku nastepnej akcji
     * @return Polaczona akcja
     */
    default <R> Action<I, R> then(Action<O, R> next) {
        return input -> next.execute(this.execute(input));
    }
}
