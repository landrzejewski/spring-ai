package pl.training.springai.agents.chain;

import pl.training.springai.agents.Action;

/**
 * Reprezentuje pojedynczy krok w lancuchu promptow (Prompt Chaining).
 *
 * Prompt Chaining to wzorzec, w ktorym:
 * 1. Zadanie jest dzielone na sekwencje krokow
 * 2. Wyjscie jednego kroku staje sie wejsciem nastepnego
 * 3. Kazdy krok moze miec wlasny prompt i przetwarzanie
 *
 * Zalety:
 * - Kontrola nad kazdym etapem przetwarzania
 * - Mozliwosc walidacji posrednich wynikow
 * - Latwiejsze debugowanie i testowanie
 * - Lepsza jakosc dzieki specjalizacji krokow
 *
 * Przyklad lancucha Code Review:
 * <pre>
 * ChainStep analyze = new ChainStep("analyze", "Znajdz problemy", analyzeAction);
 * ChainStep improve = new ChainStep("improve", "Zaproponuj poprawki", improveAction);
 * ChainStep refactor = new ChainStep("refactor", "Wygeneruj poprawiony kod", refactorAction);
 * </pre>
 *
 * @param name Nazwa kroku (do logowania i debugowania)
 * @param description Opis co robi krok
 * @param action Akcja wykonywana w tym kroku
 * @param <I> Typ wejsciowy kroku
 * @param <O> Typ wyjsciowy kroku
 */
public record ChainStep<I, O>(
        String name,
        String description,
        Action<I, O> action
) {
    /**
     * Wykonuje krok na podanym wejsciu.
     *
     * @param input Dane wejsciowe do przetworzenia
     * @return Wynik kroku (stanie sie wejsciem nastepnego kroku)
     */
    public O execute(I input) {
        return action.execute(input);
    }
}
