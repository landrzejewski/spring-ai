package pl.training.springai.agents.router;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import pl.training.springai.agents.Action;

import java.util.EnumMap;
import java.util.Map;

/**
 * Router kierujacy zgloszenia do specjalistycznych handlerow.
 *
 * Routing (Trasowanie):
 * Wzorzec, w ktorym LLM klasyfikuje wejscie i kieruje je do odpowiedniego
 * specjalisty. Kazdy specjalista ma wlasny prompt i kontekst.
 *
 * Przebieg:
 * <pre>
 * Input -> [Klasyfikator LLM] -> kategoria -> [Handler kategorii] -> Output
 * </pre>
 *
 * Przypadki uzycia:
 * - Customer Support: Billing / Technical / Sales
 * - Content Moderation: Spam / Inappropriate / Valid
 * - Code Analysis: Bug / Performance / Security
 * - Intent Recognition: Question / Command / Feedback
 *
 * Roznica od prostego if/else:
 * - LLM rozumie kontekst i niuanse
 * - Nie wymaga regul slowo-klucz
 * - Radzi sobie z niejednoznacznymi przypadkami
 *
 * Uzycie:
 * <pre>
 * var router = Router.builder(chatClient)
 *     .addHandler(BILLING, billingHandler)
 *     .addHandler(TECHNICAL, technicalHandler)
 *     .addHandler(SALES, salesHandler)
 *     .build();
 * var result = router.route("Nie moge zaplacic faktura kartą");
 * // result.category() == BILLING
 * </pre>
 */
public class Router {

    private static final Logger LOGGER = LoggerFactory.getLogger(Router.class);

    private final ChatClient chatClient;
    private final Map<TicketCategory, Action<String, String>> handlers;
    private final Action<String, String> defaultHandler;

    private Router(ChatClient chatClient,
                   Map<TicketCategory, Action<String, String>> handlers,
                   Action<String, String> defaultHandler) {
        this.chatClient = chatClient;
        this.handlers = handlers;
        this.defaultHandler = defaultHandler;
    }

    /**
     * Routuje zgloszenie do odpowiedniego handlera.
     *
     * Przebieg:
     * 1. Klasyfikacja zgloszenia przez LLM
     * 2. Wybor handlera na podstawie kategorii
     * 3. Wykonanie handlera
     * 4. Zwrot wyniku z pelnym kontekstem
     *
     * @param ticketContent Tresc zgloszenia od uzytkownika
     * @return Wynik routingu z odpowiedzia handlera
     */
    public TicketRoutingResult route(String ticketContent) {
        // 1. Klasyfikacja zgloszenia przez LLM
        LOGGER.info("Classifying ticket: {}", ticketContent.substring(0, Math.min(50, ticketContent.length())));
        var classification = classifyTicket(ticketContent);
        LOGGER.info("Classified as: {} (confidence: {})", classification.category(), classification.confidence());

        // 2. Wybor handlera na podstawie kategorii
        var handler = handlers.getOrDefault(classification.category(), defaultHandler);

        // 3. Wykonanie handlera
        LOGGER.info("Routing to handler: {}", classification.category().getDisplayName());
        var response = handler.execute(ticketContent);

        return new TicketRoutingResult(
                ticketContent,
                classification.category(),
                classification.confidence(),
                classification.reasoning(),
                response
        );
    }

    /**
     * Klasyfikuje zgloszenie do kategorii uzywajac LLM.
     */
    private TicketClassification classifyTicket(String ticketContent) {
        return chatClient.prompt()
                .system("""
                        You are a ticket classification expert for a customer support system.

                        Classify the given customer ticket into ONE of these categories:
                        - BILLING: Payment issues, invoices, refunds, subscriptions, pricing problems
                        - TECHNICAL: Bugs, errors, technical problems, troubleshooting, app not working
                        - SALES: Pricing questions, product info, upgrade requests, feature comparisons
                        - GENERAL: Other general questions, feedback, thanks, suggestions

                        Return your classification with:
                        - category: exactly one of BILLING, TECHNICAL, SALES, GENERAL
                        - confidence: your confidence level from 0.0 (uncertain) to 1.0 (certain)
                        - reasoning: brief explanation (1-2 sentences) of why you chose this category
                        """)
                .user(ticketContent)
                .call()
                .entity(TicketClassification.class);
    }

    /**
     * Wewnetrzny record do klasyfikacji przez LLM.
     */
    private record TicketClassification(
            TicketCategory category,
            double confidence,
            String reasoning
    ) {}

    /**
     * Tworzy builder dla routera.
     *
     * @param chatClient ChatClient do klasyfikacji
     * @return Builder
     */
    public static Builder builder(ChatClient chatClient) {
        return new Builder(chatClient);
    }

    /**
     * Builder dla routera.
     */
    public static class Builder {
        private final ChatClient chatClient;
        private final Map<TicketCategory, Action<String, String>> handlers = new EnumMap<>(TicketCategory.class);
        private Action<String, String> defaultHandler = input ->
                "Thank you for your message. Our team will review it shortly.";

        public Builder(ChatClient chatClient) {
            this.chatClient = chatClient;
        }

        /**
         * Dodaje handler dla kategorii.
         *
         * @param category Kategoria do obslugi
         * @param handler Akcja obslugujaca te kategorie
         * @return Builder
         */
        public Builder addHandler(TicketCategory category, Action<String, String> handler) {
            handlers.put(category, handler);
            return this;
        }

        /**
         * Ustawia domyslny handler dla nieobslugiwanych kategorii.
         *
         * @param handler Domyslna akcja
         * @return Builder
         */
        public Builder defaultHandler(Action<String, String> handler) {
            this.defaultHandler = handler;
            return this;
        }

        /**
         * Buduje router.
         *
         * @return Gotowy Router
         */
        public Router build() {
            return new Router(chatClient, new EnumMap<>(handlers), defaultHandler);
        }
    }
}
