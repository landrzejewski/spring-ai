package pl.training.springai.agents.router;

/**
 * Wynik routingu zgloszenia.
 *
 * Zawiera pelna informacje o procesie routingu:
 * 1. Oryginalny ticket - co uzytkownik napisal
 * 2. Klasyfikacja - do jakiej kategorii przypisano
 * 3. Pewnosc - jak pewny jest model swojej decyzji
 * 4. Uzasadnienie - dlaczego ta kategoria
 * 5. Odpowiedz - co zwrocil specjalistyczny handler
 *
 * Przyklad uzycia:
 * <pre>
 * var result = router.route("Nie moge zalogowac sie do konta, blad 500");
 *
 * System.out.println("Kategoria: " + result.category().getDisplayName());
 * System.out.println("Pewnosc: " + result.confidence());
 * System.out.println("Dlaczego: " + result.reasoning());
 * System.out.println("Odpowiedz: " + result.handlerResponse());
 * </pre>
 *
 * @param originalTicket Oryginalna tresc zgloszenia od uzytkownika
 * @param category Przydzielona kategoria (BILLING, TECHNICAL, SALES, GENERAL)
 * @param confidence Pewnosc klasyfikacji od 0.0 (niepewny) do 1.0 (pewny)
 * @param reasoning Uzasadnienie wyboru kategorii przez LLM
 * @param handlerResponse Odpowiedz od specjalistycznego handlera
 */
public record TicketRoutingResult(
        String originalTicket,
        TicketCategory category,
        double confidence,
        String reasoning,
        String handlerResponse
) {}
