package pl.training.agents.router;

/**
 * The categories the Router classifies into.
 * <p>
 * When an enum is the target of structured output, BeanOutputConverter turns its constants into a
 * JSON schema enum, so the model is constrained to these values and cannot invent a category. The
 * per-constant descriptions below are what the classifier is shown, which makes them part of the
 * prompt rather than documentation - vague descriptions produce vague classifications.
 */
public enum TicketCategory {

    /**
     * Payments, invoices, refunds, subscription changes.
     */
    BILLING("Billing Support", "Handles payment issues, invoices, refunds, and subscription management"),

    /**
     * Bugs and malfunctions: errors, data not saving, slow behaviour.
     */
    TECHNICAL("Technical Support", "Handles bugs, errors, technical issues, and troubleshooting"),

    /**
     * Pricing, plan comparisons, discounts, upgrades.
     */
    SALES("Sales Inquiry", "Handles pricing questions, product information, and upgrade requests"),

    /**
     * Anything else: feedback, thanks, general questions.
     */
    GENERAL("General Inquiry", "Handles general questions and feedback");

    private final String displayName;
    private final String description;

    TicketCategory(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    /**
     * A human-readable label for the UI.
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * The description shown to the classifier, so it knows what belongs in this category.
     */
    public String getDescription() {
        return description;
    }
}
