package pl.training.springai.agents.router;

/**
 * Kategorie zgloszen (ticketow) w systemie wsparcia.
 *
 * Routing (Trasowanie) to wzorzec, w ktorym:
 * 1. LLM analizuje wejscie i klasyfikuje je do kategorii
 * 2. Na podstawie kategorii wybierany jest specjalistyczny handler
 * 3. Handler przetwarza zgloszenie w sposob dostosowany do kategorii
 *
 * Zalety:
 * - Specjalizacja handlerow dla kazdej kategorii
 * - Lepsze odpowiedzi dzieki kontekstowemu przetwarzaniu
 * - Skalowalne - latwo dodac nowe kategorie
 *
 * Przyklady klasyfikacji:
 * - "Nie moge zaplacic faktura" -> BILLING
 * - "Aplikacja sie zawiesza" -> TECHNICAL
 * - "Ile kosztuje plan Enterprise?" -> SALES
 * - "Dziekuje za pomoc" -> GENERAL
 */
public enum TicketCategory {

    /**
     * Zgloszenia dotyczace platnosci, faktur, subskrypcji.
     *
     * Przyklady:
     * - Problem z platnoscia karta
     * - Prosba o zwrot
     * - Zmiana planu subskrypcji
     * - Pytanie o fakture
     */
    BILLING("Billing Support", "Handles payment issues, invoices, refunds, and subscription management"),

    /**
     * Zgloszenia techniczne - bugi, problemy z funkcjonalnoscia.
     *
     * Przyklady:
     * - Aplikacja nie dziala
     * - Blad 500 przy logowaniu
     * - Dane sie nie zapisuja
     * - Wolne dzialanie systemu
     */
    TECHNICAL("Technical Support", "Handles bugs, errors, technical issues, and troubleshooting"),

    /**
     * Zapytania sprzedazowe - cennik, oferty, upgrades.
     *
     * Przyklady:
     * - Ile kosztuje plan Pro?
     * - Jakie sa roznice miedzy planami?
     * - Czy jest zniżka dla firm?
     * - Chce upgrade do wyzszego planu
     */
    SALES("Sales Inquiry", "Handles pricing questions, product information, and upgrade requests"),

    /**
     * Ogolne zapytania - inne ktore nie pasuja do powyzszych.
     *
     * Przyklady:
     * - Podziekowanie za pomoc
     * - Ogolne pytanie o firme
     * - Feedback
     * - Sugestie funkcjonalnosci
     */
    GENERAL("General Inquiry", "Handles general questions and feedback");

    private final String displayName;
    private final String description;

    TicketCategory(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    /**
     * Zwraca czytelna nazwe kategorii.
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Zwraca opis co obsluguje dana kategoria.
     */
    public String getDescription() {
        return description;
    }
}
