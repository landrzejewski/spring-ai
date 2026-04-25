package pl.training.springai.controller;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.TranslationQueryTransformer;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionTextParser;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.web.bind.annotation.*;
import pl.training.springai.AiConfiguration;
import pl.training.springai.model.PromptRequest;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
public class RagController {

    private static final int DEFAULT_TOP_K = 5;
    private static final double DEFAULT_SIMILARITY_THRESHOLD = 0.3;

    private final OpenAiChatModel chatModel;
    private final SimpleVectorStore simpleVectorStore;
    private final PgVectorStore pgVectorStore;
    private final AiConfiguration aiConfiguration;

    public RagController(OpenAiChatModel chatModel, SimpleVectorStore simpleVectorStore, PgVectorStore pgVectorStore, AiConfiguration aiConfiguration) {
        this.chatModel = chatModel;
        this.simpleVectorStore = simpleVectorStore;
        this.pgVectorStore = pgVectorStore;
        this.aiConfiguration = aiConfiguration;
    }

    @PostMapping("init-pgvector")
    public void initPgvector() {
        aiConfiguration.initPgvector(pgVectorStore);
    }

    private VectorStore getVectorStore(String storeType) {
        return "simple".equals(storeType) ? simpleVectorStore : pgVectorStore;
    }

    private VectorStoreDocumentRetriever vectorStoreRetriever(VectorStore vectorStore, int topK, double threshold) {
        return VectorStoreDocumentRetriever.builder()
                .vectorStore(vectorStore)
                .topK(topK)
                .similarityThreshold(threshold)
                .build();
    }

    @PostMapping("basic-rag-query")
    public Flux<String> basicRagQuery(
            @RequestBody PromptRequest promptRequest,
            @RequestParam(defaultValue = "simple") String storeType) {
        var store = getVectorStore(storeType);
        var retriever = vectorStoreRetriever(store, DEFAULT_TOP_K, DEFAULT_SIMILARITY_THRESHOLD);
        var retrievalAdvisor = RetrievalAugmentationAdvisor.builder()
                .documentRetriever(retriever)
                .build();
        return ChatClient.builder(chatModel)
                .defaultAdvisors(retrievalAdvisor)
                .build()
                .prompt()
                .user(promptRequest.userPromptText())
                .stream()
                .content();
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
        var retriever = vectorStoreRetriever(store, DEFAULT_TOP_K, DEFAULT_SIMILARITY_THRESHOLD);
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
        var retriever = vectorStoreRetriever(store, DEFAULT_TOP_K, DEFAULT_SIMILARITY_THRESHOLD);
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

        var systemMessage = """
              Odpowiadaj na pytania na podstawie podanego kontekstu.
              Jesli kontekst jest pusty lub nie zawiera odpowiedzi, powiedz ze nie masz informacji.
               
              KONTEKST:
              %s
               """.formatted(context);

        return ChatClient.builder(chatModel).build()
                .prompt()
                .user(promptRequest.userPromptText())
                .system(systemMessage)
                .stream()
                .content();
    }

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

    @GetMapping("rag-debug")
    public Map<String, Object> debugPgVectorStore() {
        var searchRequest = SearchRequest.builder()
                .query("books programming")
                .topK(DEFAULT_TOP_K)
                .similarityThreshold(DEFAULT_SIMILARITY_THRESHOLD)
                .build();
        var documents = pgVectorStore.similaritySearch(searchRequest);

        var result = new LinkedHashMap<String, Object>();
        result.put("documentsCount", documents.size());
        result.put("documents", documents.stream()
                .map(doc -> {
                    var docInfo = new java.util.LinkedHashMap<String, Object>();
                    docInfo.put("id", doc.getId());
                    docInfo.put("content", doc.getText().substring(0, Math.min(100, doc.getText().length())) + "...");
                    docInfo.put("metadata", doc.getMetadata());
                    return docInfo;
                })
                .toList());

        return result;
    }

}
