package pl.training.springai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The ETL side of RAG: keeps the vector store in sync with the documents directory.
 * <p>
 * Spring AI models the pipeline as three java.util.function types:
 * <pre>
 *   DocumentReader (Supplier)  -&gt;  DocumentTransformer (Function)  -&gt;  DocumentWriter (Consumer)
 * </pre>
 * <ul>
 *   <li><b>PagePdfDocumentReader</b> emits one Document per page with file_name and page_number
 *       metadata, which is what makes citing a page possible later. Its ExtractedTextFormatter
 *       decides how clean that text is - see {@link #reader(Resource)};</li>
 *   <li><b>PdfSectionEnricher</b> and <b>TokenTextSplitter</b> are DocumentTransformers: the first
 *       adds the chapter a page belongs to, the second cuts pages into chunks and copies the page
 *       metadata onto every chunk;</li>
 *   <li><b>VectorStore</b> is the DocumentWriter: add() embeds each chunk and stores the vector
 *       next to its text and metadata.</li>
 * </ul>
 * Every chunk also carries a content_hash - a fingerprint of the file bytes and of everything that
 * shapes the chunks. Because metadata is stored alongside the vector and can be filtered on, the
 * VectorStore alone can answer "is this exact version already indexed?", with no side table.
 */
@Component
public class DocumentIngestionRunner implements ApplicationRunner {

    // Bump when the pipeline itself changes, so every file is re-indexed on the next start.
    private static final String PIPELINE_VERSION = "4";
    private static final String METADATA_CONTENT_HASH = "content_hash";
    private static final String FILE_NAME = PagePdfDocumentReader.METADATA_FILE_NAME;
    private static final int MIN_PAGE_LENGTH = 200;
    // A dot leader: "4.4. Defining query methods . . . . . . 14"
    private static final Pattern TOC_LINE = Pattern.compile("(\\.\\s?){5,}");

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentIngestionRunner.class);

    private final VectorStore vectorStore;
    private final TransactionTemplate transactionTemplate;
    private final ResourcePatternResolver resourcePatternResolver;
    private final RagProperties properties;
    private final String embeddingModel;
    private final FilterExpressionBuilder filter = new FilterExpressionBuilder();

    public DocumentIngestionRunner(VectorStore vectorStore, TransactionTemplate transactionTemplate,
                                   ResourceLoader resourceLoader, RagProperties properties,
                                   @Value("${spring.ai.openai.embedding.model}") String embeddingModel) {
        this.vectorStore = vectorStore;
        this.transactionTemplate = transactionTemplate;
        this.resourcePatternResolver = new PathMatchingResourcePatternResolver(resourceLoader);
        this.properties = properties;
        this.embeddingModel = embeddingModel;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        var documents = resourcePatternResolver.getResources(properties.documentsLocation());
        LOGGER.info("Found {} document(s) matching {}", documents.length, properties.documentsLocation());
        var fileNames = new ArrayList<Object>();
        for (var document : documents) {
            fileNames.add(document.getFilename());
            ingest(document);
        }
        removeDeleted(fileNames);
    }

    private void ingest(Resource document) throws IOException {
        var fileName = document.getFilename();
        var contentHash = fingerprint(document);
        if (isIndexed(fileName, contentHash)) {
            LOGGER.info("Skipping {} - unchanged since it was indexed", fileName);
            return;
        }
        var start = System.currentTimeMillis();
        var chunks = chunk(document, contentHash);
        // The old version is replaced atomically: PgVectorStore writes through JdbcTemplate, so
        // delete(Filter.Expression) and add() join the surrounding transaction and questions asked
        // during re-indexing keep seeing the previous chunks until the commit.
        transactionTemplate.executeWithoutResult(_ -> {
            vectorStore.delete(filter.eq(FILE_NAME, fileName).build());
            vectorStore.add(chunks);
        });
        LOGGER.info("Indexed {} - {} chunk(s) in {} s", fileName, chunks.size(),
                (System.currentTimeMillis() - start) / 1000);
    }

    private List<Document> chunk(Resource document, String contentHash) {
        var pages = reader(document).get().stream()
                .filter(this::isContent)
                .toList();
        var sections = new PdfSectionEnricher(document).apply(pages);
        return splitter().apply(sections).stream()
                .map(chunk -> withHeader(chunk, contentHash))
                .toList();
    }

    /**
     * The default ExtractedTextFormatter keeps the PDF layout: text is padded with spaces to its
     * column position, so on these manuals about 80% of the extracted characters are whitespace.
     * That noise would be split, embedded and sent to the model as context. withLeftAlignment
     * strips the padding, and the last two lines of each page - the printed page number and the
     * line break after it - are dropped as a footer.
     */
    private PagePdfDocumentReader reader(Resource document) {
        return new PagePdfDocumentReader(document, PdfDocumentReaderConfig.builder()
                .withPagesPerDocument(1)
                .withPageExtractedTextFormatter(ExtractedTextFormatter.builder()
                        .withLeftAlignment(true)
                        .withNumberOfBottomTextLinesToDelete(2)
                        .build())
                .build());
    }

    /**
     * Front matter is filtered out before splitting. Title and copyright pages carry the product
     * name and little else, and table-of-contents pages are lists of section titles - both score
     * high for any broad question and push the pages that actually explain something out of topK.
     */
    private boolean isContent(Document page) {
        var text = page.getText();
        if (text == null || text.strip().length() < MIN_PAGE_LENGTH) {
            return false;
        }
        var lines = text.lines().filter(line -> !line.isBlank()).toList();
        var tocLines = lines.stream().filter(line -> TOC_LINE.matcher(line).find()).count();
        return tocLines < lines.size() * 0.3;
    }

    /**
     * TokenTextSplitter measures chunks in tokens of the CL100K encoding, an approximation of the
     * embedding model's tokenizer, and prefers to cut at the end of a sentence. Splitting page by
     * page means a chunk never spans two pages, so its page_number is always exact.
     * <p>
     * withMaxNumChunks() is deliberately absent: it silently discards everything past the limit.
     */
    private TokenTextSplitter splitter() {
        return TokenTextSplitter.builder()
                .withChunkSize(properties.chunkSize())
                // chunks shorter than this are dropped rather than embedded as noise
                .withMinChunkLengthToEmbed(20)
                .build();
    }

    // The embedding model only sees Document.getText(), not the metadata, so the section title has
    // to be part of the text for a chunk like "Example 33." to be found by what it is about.
    private Document withHeader(Document chunk, String contentHash) {
        var section = chunk.getMetadata().get(PdfSectionEnricher.METADATA_SECTION);
        var text = section == null ? chunk.getText() : "Section: " + section + "\n\n" + chunk.getText();
        return chunk.mutate()
                .text(text)
                .metadata(METADATA_CONTENT_HASH, contentHash)
                .build();
    }

    // A metadata filter narrows the candidates before the similarity search runs, so this is an exact
    // lookup; the query text is irrelevant and similarityThresholdAll() accepts any score.
    private boolean isIndexed(String fileName, String contentHash) {
        var results = vectorStore.similaritySearch(SearchRequest.builder()
                .query(fileName)
                .topK(1)
                .similarityThresholdAll()
                .filterExpression(filter.and(
                        filter.eq(FILE_NAME, fileName),
                        filter.eq(METADATA_CONTENT_HASH, contentHash)).build())
                .build());
        return !results.isEmpty();
    }

    private void removeDeleted(List<Object> fileNames) {
        if (fileNames.isEmpty()) {
            LOGGER.warn("No documents found - leaving the vector store untouched");
            return;
        }
        vectorStore.delete(filter.nin(FILE_NAME, fileNames).build());
    }

    private String fingerprint(Resource document) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(document.getContentAsByteArray());
            var settings = String.join("|", PIPELINE_VERSION, embeddingModel, String.valueOf(properties.chunkSize()));
            digest.update(settings.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

}
