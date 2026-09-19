package pl.training.controllers;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.model.transformer.KeywordMetadataEnricher;
import org.springframework.ai.model.transformer.SummaryMetadataEnricher;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.TranslationQueryTransformer;
import org.springframework.ai.rag.retrieval.join.ConcatenationDocumentJoiner;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionTextParser;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.ai.writer.FileDocumentWriter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.*;
import pl.training.AppConfig;
import pl.training.model.PromptRequest;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
public class RagController {

    private static final int DEFAULT_TOP_K = 5;
    private static final double DEFAULT_SIMILARITY_THRESHOLD = 0.3;

    private final ChatModel chatModel;
    private final SimpleVectorStore simpleVectorStore;
    private final PgVectorStore pgVectorStore;
    private final AppConfig appConfig;

    public RagController(@Qualifier("ollamaChatModel") ChatModel chatModel, SimpleVectorStore simpleVectorStore, PgVectorStore pgVectorStore, AppConfig appConfig) {
        this.chatModel = chatModel;
        this.simpleVectorStore = simpleVectorStore;
        this.pgVectorStore = pgVectorStore;
        this.appConfig = appConfig;
    }

    @PostMapping("init-pgvector")
    public void initPgVector() {
        appConfig.initPgVector(pgVectorStore);
    }

    private VectorStore getVectorStore(String name) {
        return "simple".equals(name) ? simpleVectorStore : pgVectorStore;
    }

    @PostMapping("basic-rag-query")
    public Flux<String> basicRagQuery(
            @RequestBody PromptRequest promptRequest,
            @RequestParam(defaultValue = "simple") String storeType) {
        var store = getVectorStore(storeType);
        var retriever = getVectorStoreRetriever(store, DEFAULT_TOP_K, DEFAULT_SIMILARITY_THRESHOLD);
        var retrieverAdvisor = RetrievalAugmentationAdvisor.builder()
                .documentRetriever(retriever)
                .build();
        return ChatClient.builder(chatModel)
                .defaultAdvisors(retrieverAdvisor)
                .build()
                .prompt()
                .user(promptRequest.userPromptText())
                .stream()
                .content();
    }

    private VectorStoreDocumentRetriever getVectorStoreRetriever(VectorStore vectorStore, int topK, double threshold) {
        return VectorStoreDocumentRetriever.builder()
                .vectorStore(vectorStore)
                .topK(topK)
                .similarityThreshold(threshold)
                .build();
    }

    @PostMapping("rag-query-expander")
    public Flux<String> ragQueryExpander(
            @RequestBody PromptRequest promptRequest,
            @RequestParam(defaultValue = "simple") String storeType) {
        var store = getVectorStore(storeType);
        var queryExpander = MultiQueryExpander.builder()
                .chatClientBuilder(ChatClient.builder(chatModel))
                .numberOfQueries(3)
                .includeOriginal(true)
                .build();
        var retriever = getVectorStoreRetriever(store, DEFAULT_TOP_K, DEFAULT_SIMILARITY_THRESHOLD);
        var retrievalAdvisor = RetrievalAugmentationAdvisor.builder()
                .documentRetriever(retriever)
                .queryExpander(queryExpander)
                .build();
        return ChatClient.builder(chatModel)
                .defaultAdvisors(retrievalAdvisor)
                .build()
                .prompt()
                .user(promptRequest.userPromptText())
                .stream()
                .content();
    }

    // Query transformation. RewriteQueryTransformer turns a conversational question into a
    // search-friendly one; TranslationQueryTransformer normalises the language so a Polish
    // question can retrieve from an English corpus. Transformers run in the given order.
    @PostMapping("rag-rewrite-query")
    public Flux<String> ragRewriteQuery(
            @RequestBody PromptRequest promptRequest,
            @RequestParam(defaultValue = "simple") String storeType) {
        var store = getVectorStore(storeType);
        var chatClient = ChatClient.builder(chatModel);
        var rewriteTransformer = RewriteQueryTransformer.builder()
                .chatClientBuilder(chatClient)
                .build();
        var translationTransformer = TranslationQueryTransformer.builder()
                .chatClientBuilder(chatClient)
                .targetLanguage("English")
                .build();
        var retriever = getVectorStoreRetriever(store, DEFAULT_TOP_K, DEFAULT_SIMILARITY_THRESHOLD);
        var retrievalAdvisor = RetrievalAugmentationAdvisor.builder()
                .documentRetriever(retriever)
                .queryTransformers(rewriteTransformer,  translationTransformer)
                .build();
        return ChatClient.builder(chatModel)
                .defaultAdvisors(retrievalAdvisor)
                .build()
                .prompt()
                .user(promptRequest.userPromptText())
                .stream()
                .content();
    }

    @PostMapping("rag-filter-by-metadata")
    public Flux<String> ragFilterByMetadata(@RequestBody PromptRequest promptRequest,
                                            @RequestParam(required = false) String genre,
                                            @RequestParam(required = false) Integer year,
                                            @RequestParam(required = false) String author
    ) {
        var filterExpression = buildFilterExpression(genre, year, author);
        var searchRequestBuilder = SearchRequest.builder()
                .query(promptRequest.userPromptText())
                .topK(DEFAULT_TOP_K)
                .similarityThreshold(DEFAULT_SIMILARITY_THRESHOLD);
        if (filterExpression != null) {
            var expression = new FilterExpressionTextParser().parse(filterExpression);
            searchRequestBuilder.filterExpression(expression);
        }
        var documents = pgVectorStore.similaritySearch(searchRequestBuilder.build());
        // documents.get(0).getMetadata();
        var context = documents.stream()
                .map(Document::getFormattedContent)
                .collect(Collectors.joining());

        // Hand-written augmentation - this is what ContextualQueryAugmenter does for you
        var systemMessage = """
              Answer the question using only the context below.
              If the context is empty or does not contain the answer, say that you do not know.

              CONTEXT:
              %s
               """.formatted(context);

        return ChatClient.builder(chatModel).build()
                .prompt()
                .user(promptRequest.userPromptText())
                .system(systemMessage)
                .stream()
                .content();
    }

    // Builds the portable filter syntax understood by FilterExpressionTextParser
    private String buildFilterExpression(String genre, Integer year, String author) {
        var expr = new StringBuilder();

        if (genre != null) {
            expr.append(String.format("genre == '%s'", genre));
        }
        if (year != null) {
            if (!expr.isEmpty()) expr.append(" && ");
            expr.append(String.format("year >= %d", year));
        }
        if (author != null) {
            if (!expr.isEmpty()) expr.append(" && ");
            expr.append(String.format("author == '%s'", author));
        }

        return expr.isEmpty() ? null : expr.toString();
    }

    @PostMapping("rag-debug")
    public Map<String, Object> debugPgVectorStore(@RequestBody PromptRequest promptRequest) {
        var searchRequest = SearchRequest.builder()
                .query(promptRequest.userPromptText())
                .topK(DEFAULT_TOP_K)
                .similarityThreshold(DEFAULT_SIMILARITY_THRESHOLD)
                .build();
        var documents = pgVectorStore.similaritySearch(searchRequest);

        var result = new LinkedHashMap<String, Object>();
        result.put("documentsCount", documents.size());
        result.put("documents", documents.stream()
                .map(doc -> {
                    var docInfo = new LinkedHashMap<String, Object>();
                    docInfo.put("id", doc.getId());
                    docInfo.put("content", doc.getText().substring(0, Math.min(100, doc.getText().length())) + "...");
                    docInfo.put("metadata", doc.getMetadata());
                    return docInfo;
                })
                .toList());

        return result;
    }

    @PostMapping("rag-full-pipeline")
    public Flux<String> ragFullPipeline(
            @RequestBody PromptRequest promptRequest,
            @RequestParam(defaultValue = "simple") String storeType,
            @RequestParam(defaultValue = "true") boolean allowEmptyContext,
            @RequestParam(defaultValue = "3") int keepTopDocuments) {

        var store = getVectorStore(storeType);

        var queryExpander = MultiQueryExpander.builder()
                .chatClientBuilder(ChatClient.builder(chatModel))
                .numberOfQueries(3)
                .includeOriginal(true)
                .build();

        DocumentPostProcessor reranker = (query, documents) -> documents.stream()
                .sorted((left, right) -> Double.compare(
                        right.getScore() == null ? 0 : right.getScore(),
                        left.getScore() == null ? 0 : left.getScore()))
                .limit(keepTopDocuments)
                .toList();

        var queryAugmenter = ContextualQueryAugmenter.builder()
                .allowEmptyContext(allowEmptyContext)
                .build();

        var retrievalAdvisor = RetrievalAugmentationAdvisor.builder()
                .queryTransformers(RewriteQueryTransformer.builder()
                        .chatClientBuilder(ChatClient.builder(chatModel))
                        .build())
                .queryExpander(queryExpander)
                .documentRetriever(getVectorStoreRetriever(store, DEFAULT_TOP_K, DEFAULT_SIMILARITY_THRESHOLD))
                .documentJoiner(new ConcatenationDocumentJoiner())
                .documentPostProcessors(reranker)
                .queryAugmenter(queryAugmenter)
                .build();

        return ChatClient.builder(chatModel)
                .defaultAdvisors(retrievalAdvisor)
                .build()
                .prompt()
                .user(promptRequest.userPromptText())
                .stream()
                .content();
    }

    @Value("classpath:docs/spring-ai-overview.md")
    private Resource markdownDocument;

    @Value("classpath:docs/rag-notes.txt")
    private Resource textDocument;

    /**
     * Extract only. MarkdownDocumentReader can split on horizontal rules, keep the heading
     * hierarchy as metadata, and include or drop code blocks - so the structure of the source
     * survives into the Documents instead of being flattened into one blob.
     */
    @GetMapping("read-markdown")
    public List<Map<String, Object>> readMarkdown() {
        var config = MarkdownDocumentReaderConfig.builder()
                .withHorizontalRuleCreateDocument(true)
                .withIncludeCodeBlock(false)
                .withIncludeBlockquote(false)
                .withAdditionalMetadata("source", "spring-ai-overview.md")
                .build();
        var reader = new MarkdownDocumentReader(markdownDocument, config);
        return describe(reader.get());
    }

    @GetMapping("split")
    public List<Map<String, Object>> split(@RequestParam(defaultValue = "200") int chunkSize) {
        var documents = new TextReader(textDocument).get();
        var splitter = TokenTextSplitter.builder()
                .withChunkSize(chunkSize)
                // chunks shorter than this are dropped rather than embedded as noise
                .withMinChunkLengthToEmbed(20)
                .withKeepSeparator(false)
                .build();
        return describe(splitter.apply(documents));
    }

    /**
     * Transform with an LLM in the loop. Both enrichers call a ChatModel once per document and
     * write the result into the metadata map - not into the text, so the extra information does
     * not distort the embedding.
     * <p>
     * KeywordMetadataEnricher adds an "excerpt_keywords" entry; SummaryMetadataEnricher adds
     * "section_summary" and, for PREVIOUS/NEXT, summaries of the neighbouring chunks, which helps
     * a retrieved chunk carry some of its surrounding context.
     * <p>
     * This is expensive - one model call per document per enricher - so it belongs in an offline
     * indexing job, not in the request path.
     */
    @GetMapping("enrich")
    public List<Map<String, Object>> enrich() {
        var documents = new TextReader(textDocument).get();
        var chunks = TokenTextSplitter.builder().withChunkSize(200).build().apply(documents);

        var keywordEnricher = KeywordMetadataEnricher.builder(chatModel)
                .keywordCount(5)
                .build();
        var summaryEnricher = new SummaryMetadataEnricher(chatModel,
                List.of(SummaryMetadataEnricher.SummaryType.CURRENT),
                SummaryMetadataEnricher.DEFAULT_SUMMARY_EXTRACT_TEMPLATE,
                MetadataMode.ALL);

        return describe(summaryEnricher.apply(keywordEnricher.apply(chunks)));
    }

    /**
     * The complete pipeline, all three stages chained. The VectorStore is the DocumentWriter:
     * accept() embeds every chunk and stores the vector with its text and metadata.
     */
    @PostMapping("ingest")
    public Map<String, Object> ingest(@RequestParam(defaultValue = "300") int chunkSize) {
        var config = MarkdownDocumentReaderConfig.builder()
                .withAdditionalMetadata("source", "spring-ai-overview.md")
                .build();

        var extracted = new MarkdownDocumentReader(markdownDocument, config).get();
        var transformed = TokenTextSplitter.builder()
                .withChunkSize(chunkSize)
                .build()
                .apply(extracted);
        pgVectorStore.accept(transformed);

        return Map.of("documentsRead", extracted.size(), "chunksStored", transformed.size());
    }

    /**
     * The same pipeline with a different sink. FileDocumentWriter is useful to inspect exactly
     * what would have been embedded before paying for the embeddings.
     */
    @PostMapping("dump")
    public Map<String, Object> dump(@RequestParam(defaultValue = "target/etl-dump.txt") String fileName) {
        var chunks = TokenTextSplitter.builder()
                .withChunkSize(300)
                .build()
                .apply(new TextReader(textDocument).get());
        // withDocumentMarkers writes an index line before each document, metadataMode selects
        // which metadata ends up in the file
        new FileDocumentWriter(fileName, true, MetadataMode.ALL, false).accept(chunks);
        return Map.of("file", fileName, "chunks", chunks.size());
    }

    private List<Map<String, Object>> describe(List<Document> documents) {
        return documents.stream()
                .map(document -> Map.<String, Object>of(
                        "id", document.getId(),
                        "length", document.getText() == null ? 0 : document.getText().length(),
                        "text", abbreviate(document.getText()),
                        "metadata", document.getMetadata()))
                .toList();
    }

    private String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 180 ? text : text.substring(0, 180) + "...";
    }

}
