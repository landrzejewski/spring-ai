package pl.training.springai.chat.moderation;

/**
 * Wyjatek rzucany gdy tekst narusza polityki moderacji.
 */
public class ModerationException extends RuntimeException {

    private final String category;

    public ModerationException(String category) {
        super(String.format("Moderation failed. Content identified as %s.", category));
        this.category = category;
    }

    public String getCategory() {
        return category;
    }
}
