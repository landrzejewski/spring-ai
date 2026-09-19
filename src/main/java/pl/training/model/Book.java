package pl.training.model;

public record Book(
        String author,
        String title,
        String description,
        int publicationYear) {
}
