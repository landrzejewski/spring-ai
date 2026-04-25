package pl.training.springai.model;

public record Book(
        String author,
        String title,
        String description,
        int publicationYear) {
}
