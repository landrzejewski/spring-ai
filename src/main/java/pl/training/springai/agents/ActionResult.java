package pl.training.springai.agents;

/**
 * Generyczny wrapper dla wynikow akcji agenta.
 *
 * Przechowuje wynik operacji wraz z metadanymi o wykonaniu.
 * Uzywany do sledzenia stanu wykonania w lancuchach i orkiestratorach.
 *
 * Dzialanie:
 * 1. Akcja wykonuje operacje i mierzy czas
 * 2. W przypadku sukcesu - tworzy ActionResult.success()
 * 3. W przypadku bledu - tworzy ActionResult.failure()
 * 4. Wywolujacy moze sprawdzic success() i podjac decyzje
 *
 * Przyklad uzycia:
 * <pre>
 * long start = System.currentTimeMillis();
 * try {
 *     var result = action.execute(input);
 *     return ActionResult.success(result, System.currentTimeMillis() - start);
 * } catch (Exception e) {
 *     return ActionResult.failure(e.getMessage(), System.currentTimeMillis() - start);
 * }
 * </pre>
 *
 * @param success Czy akcja zakonczyla sie sukcesem
 * @param result Wynik akcji (moze byc null jesli success=false)
 * @param error Komunikat bledu (null jesli success=true)
 * @param executionTimeMs Czas wykonania w milisekundach
 * @param <T> Typ wyniku akcji
 */
public record ActionResult<T>(
        boolean success,
        T result,
        String error,
        long executionTimeMs
) {
    /**
     * Tworzy wynik sukcesu z danym rezultatem.
     *
     * @param result Wynik akcji
     * @param executionTimeMs Czas wykonania w ms
     * @param <T> Typ wyniku
     * @return ActionResult oznaczony jako sukces
     */
    public static <T> ActionResult<T> success(T result, long executionTimeMs) {
        return new ActionResult<>(true, result, null, executionTimeMs);
    }

    /**
     * Tworzy wynik bledu z komunikatem.
     *
     * @param error Komunikat bledu
     * @param executionTimeMs Czas wykonania w ms
     * @param <T> Typ wyniku (bedzie null)
     * @return ActionResult oznaczony jako blad
     */
    public static <T> ActionResult<T> failure(String error, long executionTimeMs) {
        return new ActionResult<>(false, null, error, executionTimeMs);
    }
}
