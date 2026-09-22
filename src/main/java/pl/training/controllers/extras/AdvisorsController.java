package pl.training.controllers.extras;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SafeGuardAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.toolsearch.ToolSearchToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.VectorStoreChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.ai.tool.toolsearch.ToolReference;
import org.springframework.ai.tool.toolsearch.ToolSearchRequest;
import org.springframework.ai.tool.toolsearch.ToolSearchResponse;
import org.springframework.ai.tool.toolsearch.index.lucene.LuceneToolIndex;
import org.springframework.ai.tool.toolsearch.index.regex.RegexToolIndex;
import org.springframework.ai.tool.toolsearch.index.vectorstore.VectorToolIndex;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.*;
import pl.training.advisors.CanaryWordAdvisor;
import pl.training.advisors.ObservabilityAdvisor;
import pl.training.advisors.PromptLeakJudgeAdvisor;
import pl.training.advisors.SafetyAdvisor;
import pl.training.advisors.TimestampAdvisor;
import pl.training.model.PromptRequest;
import pl.training.tools.DateTimeTool;
import pl.training.tools.PowerTool;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The advisor chain in detail.
 * <p>
 * Every ChatClient request runs through an ordered chain of advisors that wraps the model call.
 * An advisor can rewrite the request on the way in, transform the response on the way out, or
 * short-circuit the chain entirely. Memory, RAG and tool calling are not special cases in the
 * framework - they are ordinary advisors.
 * <p>
 * Two marker interfaces control how the chain assembles itself:
 * <ul>
 *   <li>{@link org.springframework.ai.chat.client.advisor.api.ToolAdvisor} - an advisor that
 *       handles tool calls. Its presence stops ChatClient from auto-registering the default
 *       ToolCallingAdvisor, so the two do not fight over the same loop.</li>
 *   <li>{@link org.springframework.ai.chat.client.advisor.api.MemoryAdvisor} - an advisor that
 *       manages conversation history. The tool-calling loop detects it and suppresses its own
 *       internal history handling, so intermediate tool messages are not persisted twice.</li>
 * </ul>
 */
@RestController
@RequestMapping("advisors")
public class AdvisorsController {

    private final OpenAiChatModel chatModel;
    private final OllamaChatModel ollamaChatModel;
    private final SimpleVectorStore booksVectorStore;
    // A separate store for conversation history - sharing the books catalog would let chat
    // fragments leak into every RAG query that searches it
    private final SimpleVectorStore chatMemoryVectorStore;
    private final EmbeddingModel embeddingModel;
    private final ToolCallingManager toolCallingManager;
    private final ObjectProvider<ToolCallbackProvider> mcpToolCallbackProvider;
    private final ObservationRegistry observationRegistry;

    // Appended to the system message by the tool-search advisor. The built-in default is a single
    // sentence, and a model without reasoning tends to ignore it and answer "I have no access to
    // that" instead of searching - so the demo spells out when to search. The library also ships a
    // longer template, DEFAULT_SYSTEM_PROMPT_SUFFIX_LARGE.md, meant to be customised per application
    private static final String TOOL_SEARCH_SYSTEM_SUFFIX = """


            No tools are loaded into your context up front. Whenever a request needs live data or a \
            computation you cannot do reliably yourself - the current date or time, arithmetic, \
            searching or fetching web content - first call `toolSearchTool` with a short description \
            of the capability you need, then invoke the tool it returns. Never claim that you lack \
            access to such a capability before searching for it.""";

    public AdvisorsController(OpenAiChatModel chatModel,
                              OllamaChatModel ollamaChatModel,
                              SimpleVectorStore booksVectorStore,
                              EmbeddingModel embeddingModel,
                              ToolCallingManager toolCallingManager,
                              ObjectProvider<ToolCallbackProvider> mcpToolCallbackProvider,
                              ObservationRegistry observationRegistry) {
        this.chatModel = chatModel;
        this.ollamaChatModel = ollamaChatModel;
        this.booksVectorStore = booksVectorStore;
        this.embeddingModel = embeddingModel;
        this.toolCallingManager = toolCallingManager;
        this.mcpToolCallbackProvider = mcpToolCallbackProvider;
        this.observationRegistry = observationRegistry;
        this.chatMemoryVectorStore = SimpleVectorStore.builder(embeddingModel).build();
    }

    /**
     * A hand-written advisor on a streaming call. ObservabilityAdvisor implements both CallAdvisor
     * and StreamAdvisor - with stream() only adviseStream() runs, and since there is no single
     * response to inspect, the usage is taken from the last chunk once the Flux completes.
     * <p>
     * The built-in counterpart, SimpleLoggerAdvisor, is used in /chat-with-advisors. In production
     * neither is needed: Micrometer observations carry the same data.
     */
    @PostMapping("observability")
    public Flux<String> observability(@RequestBody PromptRequest promptRequest) {
        return ChatClient.builder(chatModel, observationRegistry, null, null).build()
                .prompt(promptRequest.userPromptText())
                .advisors(new ObservabilityAdvisor())
                .stream()
                .content();
    }

    /**
     * QuestionAnswerAdvisor is naive RAG in a single line: it runs a similarity search for the
     * user question and appends the matching documents to the prompt.
     * <p>
     * Compare with RetrievalAugmentationAdvisor in RagController - the modular counterpart, where
     * query transformation, expansion, retrieval, joining, augmentation and post-processing are
     * separate, replaceable components. Use this one when a plain top-k lookup is enough.
     */
    @PostMapping("question-answer")
    public String questionAnswer(@RequestBody PromptRequest promptRequest) {
        var advisor = QuestionAnswerAdvisor.builder(booksVectorStore)
                .searchRequest(SearchRequest.builder()
                        .topK(3)
                        .similarityThreshold(0.5)
                        .build())
                .build();
        return ChatClient.builder(chatModel, observationRegistry, null, null).build()
                .prompt(promptRequest.userPromptText())
                .advisors(advisor)
                .call()
                .content();
    }

    /**
     * The other chat-memory strategy. MessageChatMemoryAdvisor replays the last N messages
     * verbatim; VectorStoreChatMemoryAdvisor embeds every exchange and retrieves only the
     * fragments semantically related to the current question, injecting them as text into the
     * system message. Each fragment is stored with the conversation id in its metadata, and the
     * search is filtered by it, so conversations do not see each other.
     * <p>
     * The trade-off: the window advisor is exact but bounded by the context size, the vector
     * advisor scales to arbitrarily long histories but can miss something the model needed.
     */
    @PostMapping("vector-memory/{conversationId}")
    public String vectorMemory(@RequestBody PromptRequest promptRequest,
                               @PathVariable String conversationId) {
        var advisor = VectorStoreChatMemoryAdvisor.builder(chatMemoryVectorStore)
                .defaultTopK(5)
                .build();
        return ChatClient.builder(chatModel, observationRegistry, null, null).build()
                .prompt(promptRequest.userPromptText())
                .advisors(advisor)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .content();
    }

    /**
     * Advisor ordering. The chain is sorted ascending by getOrder(), so a lower value runs earlier
     * - closer to the caller, further from the model.
     * <p>
     * Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER is the slot reserved for memory advisors. In
     * Spring AI 2.0 it moved from HIGHEST_PRECEDENCE + 1000 to HIGHEST_PRECEDENCE + 200, which
     * places memory <em>outside</em> the tool-calling loop: the history is resolved once per user
     * turn rather than on every internal tool round trip.
     * <p>
     * Note that every advisor in this project, and the built-in guardrails, default to 0 - with
     * equal values the order in which they were registered decides, which is rarely what a
     * guardrail wants. Set an explicit order whenever two advisors depend on each other.
     */
    @GetMapping("order")
    public Map<String, Integer> order() {
        var advisors = List.<Advisor>of(
                MessageChatMemoryAdvisor.builder(MessageWindowChatMemory.builder().build()).build(),
                SafeGuardAdvisor.builder().sensitiveWords(List.of()).build(),
                CanaryWordAdvisor.builder().build(),
                SafetyAdvisor.builder().ollamaChatModel(ollamaChatModel).build(),
                PromptLeakJudgeAdvisor.builder().ollamaChatModel(ollamaChatModel).build(),
                SimpleLoggerAdvisor.builder().build(),
                new ObservabilityAdvisor(),
                new TimestampAdvisor()
        );
        var order = new LinkedHashMap<String, Integer>();
        order.put("HIGHEST_PRECEDENCE", Integer.MIN_VALUE);
        order.put("DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER", Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER);
        advisors.stream()
                .sorted((first, second) -> Integer.compare(first.getOrder(), second.getOrder()))
                .forEach(advisor -> order.put(advisor.getName(), advisor.getOrder()));
        return order;
    }

    /**
     * Tool calling is itself an advisor, auto-registered by ChatClient whenever a request may need
     * it. Disabling the auto-registration leaves the tool call unresolved: the model's request
     * comes back as-is, with no execution and no second round trip - so the response carries the
     * tool calls instead of an answer.
     * <p>
     * This is what a bare ChatModel does in Spring AI 2.0 - the loop is no longer built into the
     * model implementations. Turn it off when you want to drive the loop yourself with a
     * ToolCallingManager, or to inspect what the model actually asked for.
     */
    @PostMapping("no-tool-loop")
    public Map<String, Object> noToolLoop(@RequestBody PromptRequest promptRequest) {
        var output = ChatClient.builder(chatModel, observationRegistry, null, null).build()
                .prompt(promptRequest.userPromptText())
                .tools(new DateTimeTool())
                .advisors(AdvisorParams.toolCallingAdvisorAutoRegister(false))
                .call()
                .chatResponse()
                .getResult()
                .getOutput();
        var toolCalls = output.getToolCalls().stream()
                .map(toolCall -> Map.of("name", toolCall.name(), "arguments", toolCall.arguments()))
                .toList();
        return Map.of(
                "text", String.valueOf(output.getText()),
                "toolCalls", toolCalls);
    }

    /**
     * Tool preselection with the Tool Search Tool, new in Spring AI 2.0.
     * <p>
     * Normally every tool definition - name, description and the full JSON schema of its
     * parameters - travels with every request. A few tools are cheap; with MCP servers plugged in
     * the catalog grows to dozens, which costs context tokens on every round trip and measurably
     * degrades the model's choice of tool. Preselection sends the model none of them. Instead,
     * ToolSearchToolCallingAdvisor replaces the default ToolCallingAdvisor (it is a ToolAdvisor, so
     * ChatClient skips the auto-registration) and:
     * <ul>
     *   <li>at the start of the loop resolves all tool definitions and indexes them under the
     *       session id, taken from the advisor context under ChatMemory.CONVERSATION_ID - the same
     *       key the memory advisors use; a missing key is an assertion error. The index is cached
     *       per session and refreshed only when a fingerprint of the tool set changes,</li>
     *   <li>appends a suffix to the system message introducing a single tool, toolSearchTool,</li>
     *   <li>before every model call replaces the tool list with toolSearchTool plus the tools named
     *       in earlier search responses, accumulated across the loop.</li>
     * </ul>
     * The model never sees the catalog: it searches with a natural-language query, receives tool
     * names, the advisor expands them into full definitions on the next iteration, and only then
     * the real call happens. The price is one extra round trip per discovery. Whether the model
     * searches at all is decided by the system-message suffix the advisor appends - the default is
     * a single sentence, and with it qwen answered "I don't have access to the current time" without
     * ever calling toolSearchTool, hence the explicit instruction in TOOL_SEARCH_SYSTEM_SUFFIX.
     * <p>
     * The index sees nothing but the tool name and description - the parameter schema is not
     * searchable - so the quality of the descriptions is the quality of the retrieval. The index
     * itself is a pluggable ToolIndex with three built-in strategies, selectable here with
     * ?index=:
     * <ul>
     *   <li>regex (default) - RegexToolIndex tokenizes the query, drops stop words and builds a
     *       case-insensitive alternation, (?i)(token1|token2|...), matched against names and
     *       descriptions; a hit in the name weighs twice a hit in the description and earlier
     *       positions score higher. No dependencies and fully deterministic, but no synonyms and no
     *       stemming: "squared" does not match "square".</li>
     *   <li>lucene - LuceneToolIndex, an in-memory BM25 full-text index with a minimum score
     *       threshold. Better ranking on longer descriptions, still purely lexical.</li>
     *   <li>vector - VectorToolIndex embeds the descriptions into a VectorStore and filters by
     *       session. Understands paraphrases, at the cost of embedding calls on indexing and on
     *       every search.</li>
     * </ul>
     * Everything is created per request here so nothing leaks between calls and the searches can be
     * captured. In production the advisor is a singleton and the fingerprint cache makes re-indexing
     * a no-op; alternatively spring.ai.chat.client.tool-search-advisor.enabled=true together with
     * tool-index-type=regex|lucene|vector swaps the ToolCallingAdvisor for every ChatClient in the
     * application.
     * <p>
     * The response carries the answer and every search the model issued - the query, the matches and
     * their scores - captured by wrapping the index, because toolSearchTool returns only names to the
     * model and the intermediate tool messages never reach the ChatClient response. Two side effects
     * to expect: getCurrentTime is returnDirect, so when the model picks it the raw timestamp comes
     * back without a final model turn, and spring.ai.tools.limits.max-total-tool-calls counts the
     * searches too.
     */
    @PostMapping("tool-search/{conversationId}")
    public Map<String, Object> toolSearch(@RequestBody PromptRequest promptRequest,
                                         @PathVariable String conversationId,
                                         @RequestParam(defaultValue = "regex") String index) {
        var recordingIndex = new RecordingToolIndex(toolIndex(index));
        var advisor = ToolSearchToolCallingAdvisor.builder()
                .toolCallingManager(toolCallingManager)
                .toolIndex(recordingIndex)
                .systemMessageSuffix(TOOL_SEARCH_SYSTEM_SUFFIX)
                .maxResults(3)
                .build();
        var text = ChatClient.builder(chatModel, observationRegistry, null, null).build()
                .prompt(promptRequest.userPromptText()).tools(toolCatalog())
                .advisors(advisor)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .content();
        return Map.of(
                "text", String.valueOf(text),
                "toolSearches", recordingIndex.searches());
    }

    /**
     * The index in isolation, without a model: the same catalog goes into a throwaway session and
     * the prompt text is used directly as the search query. Use it to see what each strategy would
     * hand the model for a given phrasing - "compute 12 squared" finds the power tool with the
     * vector index and nothing with regex or lucene. With regex the generated pattern is logged at
     * DEBUG by org.springframework.ai.tool.toolsearch.
     */
    @PostMapping("tool-search-preview")
    public ToolSearchResponse toolSearchPreview(@RequestBody PromptRequest promptRequest,
                                                @RequestParam(defaultValue = "regex") String index) {
        var toolIndex = toolIndex(index);
        var sessionId = UUID.randomUUID().toString();
        toolIndex.indexTools(sessionId, toolCatalog().stream()
                .map(ToolCallback::getToolDefinition)
                .map(definition -> ToolReference.builder()
                        .toolName(definition.name())
                        .summary(definition.description())
                        .build())
                .toList());
        return toolIndex.search(new ToolSearchRequest(sessionId, promptRequest.userPromptText(), 5, null));
    }

    // The local tools plus whatever the MCP servers expose. PowerTool's @Description is
    // jdk.jfr.Description, which Spring ignores - without an explicit description the callback would
    // be described as "power" and no query about squares would ever find it
    private List<ToolCallback> toolCatalog() {
        var catalog = new ArrayList<>(List.of(ToolCallbacks.from(new DateTimeTool())));
        catalog.add(FunctionToolCallback.builder("power", new PowerTool())
                .description("Calculates the square of a number (value * value)")
                .inputType(Double.class)
                .build());
        mcpToolCallbackProvider.stream()
                .flatMap(provider -> Arrays.stream(provider.getToolCallbacks()))
                .forEach(catalog::add);
        return catalog;
    }

    private ToolIndex toolIndex(String type) {
        return switch (type) {
            case "regex" -> new RegexToolIndex();
            case "lucene" -> new LuceneToolIndex();
            case "vector" -> new VectorToolIndex(SimpleVectorStore.builder(embeddingModel).build());
            default -> throw new IllegalArgumentException("Unknown tool index type: " + type);
        };
    }

    /**
     * Decorator that records every search the advisor runs against the index - the only place the
     * model's query, the matched tools and their scores are visible.
     */
    private static class RecordingToolIndex implements ToolIndex {

        private final ToolIndex delegate;
        private final List<ToolSearchResponse> searches = new CopyOnWriteArrayList<>();

        RecordingToolIndex(ToolIndex delegate) {
            this.delegate = delegate;
        }

        @Override
        public void indexTool(String sessionId, ToolReference toolReference) {
            delegate.indexTool(sessionId, toolReference);
        }

        // The interface default loops over indexTool() and would bypass the batch path of the
        // Lucene and vector indexes
        @Override
        public void indexTools(String sessionId, List<ToolReference> toolReferences) {
            delegate.indexTools(sessionId, toolReferences);
        }

        @Override
        public ToolSearchResponse search(ToolSearchRequest toolSearchRequest) {
            var response = delegate.search(toolSearchRequest);
            searches.add(response);
            return response;
        }

        @Override
        public void clearIndex(String sessionId) {
            delegate.clearIndex(sessionId);
        }

        List<ToolSearchResponse> searches() {
            return searches;
        }

    }

}
