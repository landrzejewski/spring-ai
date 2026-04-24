package pl.training.springai.agents.parallelization;

/**
 * Zadanie analizy kodu do rownoleglego przetwarzania.
 *
 * Parallelization wymaga wspolnego inputu dla wszystkich taskow.
 * Ten record dostarcza wszystkie informacje potrzebne do analizy:
 * - Kod zrodlowy do analizy
 * - Jezyk programowania (wplywa na specyfike analizy)
 * - Kontekst biznesowy (pomaga zrozumiec intencje)
 *
 * Przyklad uzycia:
 * <pre>
 * var request = new CodeAnalysisRequest(
 *     "public void process(String input) { ... }",
 *     "Java",
 *     "Service przetwarzajacy dane uzytkownikow"
 * );
 * var result = codeAnalyzer.execute(request);
 * </pre>
 *
 * @param sourceCode Kod zrodlowy do analizy
 * @param language Jezyk programowania (Java, Python, JavaScript, etc.)
 * @param context Dodatkowy kontekst biznesowy (np. opis projektu, cel kodu)
 */
public record CodeAnalysisRequest(
        String sourceCode,
        String language,
        String context
) {}
