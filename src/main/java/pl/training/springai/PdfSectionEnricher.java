package pl.training.springai;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentTransformer;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * A custom DocumentTransformer - the ETL step that sits between a DocumentReader and a
 * DocumentWriter and may rewrite text or metadata. This one adds the "section" metadata key:
 * the path of the PDF outline entry a page belongs to, e.g.
 * "Chapter 2. Core Technologies > 2.1. The IoC Container".
 * <p>
 * PagePdfDocumentReader knows the page number but not the chapter, and ParagraphPdfDocumentReader,
 * which does read the outline, cuts the text by layout regions and misplaces section boundaries
 * on these manuals. Reading pages and borrowing only the titles from the outline keeps both the
 * exact page for citations and the chapter context for retrieval.
 */
public class PdfSectionEnricher implements DocumentTransformer {

    public static final String METADATA_SECTION = "section";

    private static final int MAX_DEPTH = 4;
    private static final String SEPARATOR = " > ";
    // A heading within the top tenth of a page is treated as the one the page opens with.
    private static final double PAGE_TOP = 0.1;

    // Keyed by position in the document: page number plus how far down the page the heading sits.
    private final NavigableMap<Double, Section> sections = new TreeMap<>();

    public PdfSectionEnricher(Resource pdf) {
        try (var document = Loader.loadPDF(pdf.getContentAsByteArray())) {
            var outline = document.getDocumentCatalog().getDocumentOutline();
            if (outline != null) {
                collect(document, outline, "", 1);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    @Override
    public List<Document> apply(List<Document> pages) {
        return pages.stream()
                .map(this::enrich)
                .toList();
    }

    /**
     * The section a page opens in, followed by the titles of the sections that start further down,
     * e.g. "Chapter 4 > 4.7. Spring Data extensions > 4.7.1. Querydsl Extension | 4.7.2. Web support".
     */
    private Document enrich(Document page) {
        if (!(page.getMetadata().get(PagePdfDocumentReader.METADATA_START_PAGE_NUMBER) instanceof Number number)) {
            return page;
        }
        var top = number.intValue() + PAGE_TOP;
        var opening = sections.floorEntry(top);
        if (opening == null) {
            return page;
        }
        var startingLower = sections.subMap(top, false, number.intValue() + 1.0, false).values().stream()
                .map(Section::title)
                .distinct()
                .collect(Collectors.joining("; "));
        var section = startingLower.isEmpty() ? opening.getValue().path() : opening.getValue().path() + " | " + startingLower;
        return page.mutate().metadata(METADATA_SECTION, section).build();
    }

    // Visited in document order, so for entries at the same position the deepest one wins.
    private void collect(PDDocument document, PDOutlineNode node, String parentPath, int depth) throws IOException {
        for (var item : node.children()) {
            var title = item.getTitle() == null ? "" : item.getTitle().strip();
            var path = parentPath.isEmpty() ? title : parentPath + SEPARATOR + title;
            var page = item.findDestinationPage(document);
            if (page != null && !title.isEmpty()) {
                var position = document.getPages().indexOf(page) + 1 + offsetOnPage(document, item, page);
                sections.put(position, new Section(title, path));
            }
            if (depth < MAX_DEPTH) {
                collect(document, item, path, depth + 1);
            }
        }
    }

    // 0.0 for the top of the page, approaching 1.0 towards the bottom; 0.0 when the outline entry
    // points at the page without a position.
    private double offsetOnPage(PDDocument document, PDOutlineItem item, PDPage page) throws IOException {
        var destination = item.getDestination();
        if (destination == null && item.getAction() instanceof PDActionGoTo goTo) {
            destination = goTo.getDestination();
        }
        if (destination instanceof PDNamedDestination named) {
            destination = document.getDocumentCatalog().findNamedDestinationPage(named);
        }
        if (destination instanceof PDPageXYZDestination xyz && xyz.getTop() >= 0) {
            var height = page.getMediaBox().getHeight();
            return Math.clamp(1.0 - xyz.getTop() / height, 0.0, 0.999);
        }
        return 0.0;
    }

    private record Section(String title, String path) {
    }

}
