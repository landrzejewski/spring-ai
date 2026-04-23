package pl.training.springai.chat.model;

public record Book(
        String author,
        String title,
        String description,
        int publicationYear) {
}
