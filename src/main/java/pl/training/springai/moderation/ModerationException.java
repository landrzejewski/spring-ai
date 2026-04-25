package pl.training.springai.moderation;

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
