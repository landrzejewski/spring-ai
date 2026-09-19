package pl.training.agents.router;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import pl.training.agents.Action;

import java.util.EnumMap;
import java.util.Map;

/**
 * Classifies an incoming request with one model call, then hands it to a specialised handler.
 * <pre>
 * Input -> [LLM classifier] -> category -> [category handler] -> Output
 * </pre>
 * The classification uses structured output, so the category arrives as an enum constant rather
 * than as prose to be matched. Two model calls happen per request - a cheap, narrow classification
 * and an expensive, specialised answer - which is usually cheaper than one prompt carrying the
 * instructions for every category at once.
 * <p>
 * The reason to classify with a model rather than with keyword rules is that it reads intent:
 * "I cannot pay with my card" is billing without containing the word invoice, and an ambiguous
 * message still gets a category plus a confidence score the caller can act on.
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
     * Classifies the ticket and runs the matching handler.
     *
     * @param ticketContent the customer message
     * @return the category, the classifier's reasoning and the handler's reply
     */
    public TicketRoutingResult route(String ticketContent) {
        // 1. Classify with the model
        LOGGER.info("Classifying ticket: {}", ticketContent.substring(0, Math.min(50, ticketContent.length())));
        var classification = classifyTicket(ticketContent);
        LOGGER.info("Classified as: {} (confidence: {})", classification.category(), classification.confidence());

        // 2. Pick the handler for the category
        var handler = handlers.getOrDefault(classification.category(), defaultHandler);

        // 3. Run it
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
     * The classification call. entity() binds the reply to a record, so the category comes back as
     * a typed enum instead of a string that would have to be matched.
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
     * The structured output type of the classification call. Asking for the reasoning alongside the
     * category is not decoration: it makes a misclassification diagnosable, and models classify
     * more accurately when required to justify the choice.
     */
    private record TicketClassification(
            TicketCategory category,
            double confidence,
            String reasoning
    ) {}

    /**
     * @param chatClient the client used for the classification call
     * @return a builder
     */
    public static Builder builder(ChatClient chatClient) {
        return new Builder(chatClient);
    }

    /**
     * Assembles the category-to-handler map.
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
         * @param category the category to handle
         * @param handler the action for it, typically a ChatClient call with its own system prompt
         * @return the builder
         */
        public Builder addHandler(TicketCategory category, Action<String, String> handler) {
            handlers.put(category, handler);
            return this;
        }

        /**
         * @param handler the fallback for categories with no registered handler
         * @return the builder
         */
        public Builder defaultHandler(Action<String, String> handler) {
            this.defaultHandler = handler;
            return this;
        }

        /**
         * @return the assembled router
         */
        public Router build() {
            return new Router(chatClient, new EnumMap<>(handlers), defaultHandler);
        }
    }
}
