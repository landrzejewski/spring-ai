package pl.training.agents.router;

/**
 * The full outcome of a routing decision, classification included rather than discarded.
 * <p>
 * The confidence score is the useful part in production: a low-confidence classification is the
 * signal to fall back to a human or to the general handler instead of committing to a specialist.
 * It is the model's own estimate, so treat it as a hint, not a measurement.
 *
 * @param originalTicket the customer message
 * @param category the assigned category
 * @param confidence the model's confidence, 0.0 to 1.0
 * @param reasoning why the model chose this category
 * @param handlerResponse the specialised handler's reply
 */
public record TicketRoutingResult(
        String originalTicket,
        TicketCategory category,
        double confidence,
        String reasoning,
        String handlerResponse
) {}
