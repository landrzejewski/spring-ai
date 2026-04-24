package pl.training.springai.agents.parallelization;

import java.util.List;

/**
 * Zagregowany wynik rownoleglej analizy kodu.
 *
 * Parallelization (Zrownoleglenie):
 * Wzorzec, w ktorym zadanie jest dzielone na niezalezne podzadania
 * wykonywane rownolegle, a wyniki sa agregowane.
 *
 * Trzy aspekty analizy (niezalezne):
 * 1. Quality - jakosc kodu, czytelnosc, struktura
 * 2. Security - podatnosci, bezpieczenstwo danych
 * 3. Performance - wydajnosc, zlozonosc obliczeniowa
 *
 * Przypadki uzycia:
 * - Code Analysis: Quality + Security + Performance (niezalezne aspekty)
 * - Document Processing: Summarize + Extract entities + Translate
 * - Content Generation: Multiple variations simultaneously
 *
 * Przyklad uzycia:
 * <pre>
 * var result = codeAnalyzer.execute(request);
 *
 * System.out.println("Quality score: " + result.qualityAnalysis().score());
 * System.out.println("Security score: " + result.securityAnalysis().score());
 * System.out.println("Performance score: " + result.performanceAnalysis().score());
 * System.out.println("Overall: " + result.overallScore());
 * </pre>
 *
 * @param qualityAnalysis Wynik analizy jakosci kodu
 * @param securityAnalysis Wynik analizy bezpieczenstwa
 * @param performanceAnalysis Wynik analizy wydajnosci
 * @param overallScore Ogolna ocena (srednia wazona: 40% quality, 35% security, 25% performance)
 * @param executionTimeMs Calkowity czas wykonania (najdluzszy watek)
 */
public record CodeAnalysisResult(
        QualityAnalysis qualityAnalysis,
        SecurityAnalysis securityAnalysis,
        PerformanceAnalysis performanceAnalysis,
        double overallScore,
        long executionTimeMs
) {
    /**
     * Analiza jakosci kodu.
     *
     * Ocenia:
     * - Code smells (dlugie metody, duplikacja, magic numbers)
     * - Czytelnosc i dokumentacja
     * - Zgodnosc ze standardami kodowania
     * - Struktura i organizacja kodu
     *
     * @param score Ocena od 0.0 (bardzo zla) do 1.0 (doskonala)
     * @param issues Lista znalezionych problemow jakosciowych
     * @param suggestions Sugestie poprawy jakosci
     */
    public record QualityAnalysis(
            double score,
            List<String> issues,
            List<String> suggestions
    ) {}

    /**
     * Analiza bezpieczenstwa.
     *
     * Ocenia:
     * - SQL Injection
     * - XSS (Cross-Site Scripting)
     * - Problemy z autoryzacja/uwierzytelnianiem
     * - Wycieki danych
     * - Hardcoded secrets
     *
     * @param score Ocena od 0.0 (krytyczne podatnosci) do 1.0 (bezpieczny)
     * @param vulnerabilities Lista znalezionych podatnosci
     * @param severity Najwyzszy poziom zagrozenia (LOW, MEDIUM, HIGH, CRITICAL)
     */
    public record SecurityAnalysis(
            double score,
            List<String> vulnerabilities,
            String severity
    ) {}

    /**
     * Analiza wydajnosci.
     *
     * Ocenia:
     * - Zlozonosc czasowa (Big O)
     * - Zuzycie pamieci
     * - Waskie gardla I/O
     * - Mozliwosci cachowania
     * - N+1 queries
     *
     * @param score Ocena od 0.0 (bardzo wolny) do 1.0 (optymalny)
     * @param bottlenecks Zidentyfikowane waskie gardla
     * @param optimizations Propozycje optymalizacji
     */
    public record PerformanceAnalysis(
            double score,
            List<String> bottlenecks,
            List<String> optimizations
    ) {}
}
