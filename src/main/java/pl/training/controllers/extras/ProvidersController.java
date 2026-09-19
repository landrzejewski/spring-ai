package pl.training.controllers.extras;

import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import io.micrometer.context.ContextExecutorService;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.anthropic.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.training.model.PromptRequest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Provider portability, which is the point of the ChatModel abstraction: the same ChatClient code
 * runs against OpenAI, Anthropic and a local Ollama model, and only the options differ.
 * <p>
 * The Anthropic module was rewritten in Spring AI 2.0 on top of the official Anthropic Java SDK.
 * The hand-rolled {@code org.springframework.ai.anthropic.api} package is gone - an
 * AnthropicChatModel is now built with {@code AnthropicChatModel.builder().apiKey(...)} - and the
 * provider-specific capabilities below came with the change. Note also that the default maxTokens
 * went from 500 to 4096.
 * <p>
 * The Anthropic beans are optional: without an API key the module is not usable and these
 * endpoints report that instead of failing at startup. The model is set in application.yml
 * (spring.ai.anthropic.chat.options.model) - the Spring AI default, Haiku 4.5, supports neither
 * adaptive thinking nor the web search tool variant used below.
 * <p>
 * One trap worth knowing: with extended thinking every thinking block becomes a Generation of its
 * own, placed <em>before</em> the answer. getResult() returns the first Generation, so on a
 * thinking model it returns the reasoning, not the answer - hence {@link #answer(ChatResponse)}.
 */
@RestController
@RequestMapping("providers")
public class ProvidersController {

    private final OpenAiChatModel openAiChatModel;
    private final OllamaChatModel ollamaChatModel;
    private final ObjectProvider<AnthropicChatModel> anthropicChatModel;
    private final boolean anthropicConfigured;
    private final ObservationRegistry observationRegistry;

    public ProvidersController(OpenAiChatModel openAiChatModel,
                               OllamaChatModel ollamaChatModel,
                               ObjectProvider<AnthropicChatModel> anthropicChatModel,
                               @Value("${spring.ai.anthropic.api-key:}") String anthropicApiKey,
                               ObservationRegistry observationRegistry) {
        this.openAiChatModel = openAiChatModel;
        this.ollamaChatModel = ollamaChatModel;
        this.anthropicChatModel = anthropicChatModel;
        this.anthropicConfigured = anthropicApiKey != null && !anthropicApiKey.isBlank();
        this.observationRegistry = observationRegistry;
    }

    /**
     * The same prompt through three providers. Identical ChatClient code each time - the only
     * thing that changes is which ChatModel the builder is given.
     */
    @PostMapping("compare")
    public Map<String, Object> compare(@RequestBody PromptRequest promptRequest) {
        var results = new LinkedHashMap<String, Object>();
        var models = new LinkedHashMap<String, ChatModel>();
        models.put("openai", openAiChatModel);
        models.put("ollama", ollamaChatModel);
        if (anthropicConfigured) {
            anthropicChatModel.ifAvailable(model -> models.put("anthropic", model));
        }

        // ContextExecutorService przenosi biezacy trace do watkow wirtualnych - inaczej spany modeli trafiaja do osobnych trace'ow
        try (var executor = ContextExecutorService.wrap(Executors.newVirtualThreadPerTaskExecutor())) {
            var futures = new LinkedHashMap<String, java.util.concurrent.Future<Map<String, Object>>>();
            models.forEach((name, model) -> futures.put(name, executor.submit(() -> ask(model, promptRequest))));
            futures.forEach((name, future) -> {
                try {
                    results.put(name, future.get(120, TimeUnit.SECONDS));
                }
                catch (Exception exception) {
                    results.put(name, Map.of("error", String.valueOf(exception.getMessage())));
                }
            });
        }
        return results;
    }

    private Map<String, Object> ask(ChatModel model, PromptRequest promptRequest) {
        var start = System.currentTimeMillis();
        var response = ChatClient.builder(model, observationRegistry, null, null).build()
                .prompt(promptRequest.userPromptText())
                .call()
                .chatResponse();
        var usage = response.getMetadata().getUsage();
        return Map.of(
                "model", String.valueOf(response.getMetadata().getModel()),
                "answer", answer(response),
                "totalTokens", usage == null ? -1 : usage.getTotalTokens(),
                "durationMs", System.currentTimeMillis() - start);
    }

    /**
     * Prompt caching. Anthropic can cache a prefix of the request server-side, so a long system
     * message or a large tool set is charged once and then re-read at a fraction of the price on
     * every subsequent call.
     * <p>
     * AnthropicCacheStrategy decides what gets a cache breakpoint: SYSTEM_ONLY, TOOLS_ONLY,
     * SYSTEM_AND_TOOLS or CONVERSATION_HISTORY. Send the same request twice and compare the
     * counters: the first call writes the cache (cacheWriteInputTokens), the second reads it
     * (cacheReadInputTokens) - both are exposed by the portable Usage since Spring AI 2.0.
     * <p>
     * A prefix shorter than the model minimum is silently not cached - no error, just zeros. The
     * minimum is 512 tokens on Claude Opus 5, 1024 on Sonnet 5 and 4096 on Haiku 4.5, which is why
     * the system message carries the whole books catalog and not just a few notes.
     */
    @PostMapping("anthropic/prompt-caching")
    public Map<String, Object> promptCaching(@RequestBody PromptRequest promptRequest) {
        return withAnthropic(model -> {
            var response = ChatClient.builder(model, observationRegistry, null, null).build()
                    .prompt()
                    .system(spec -> spec
                            .text(SYSTEM_MESSAGE)
                            .param("catalog", booksCatalogText()))
                    .user(promptRequest.userPromptText())
                    .options(AnthropicChatOptions.builder()
                            .cacheOptions(AnthropicCacheOptions.builder()
                                    .strategy(AnthropicCacheStrategy.SYSTEM_ONLY)
                                    .build()))
                    .call()
                    .chatResponse();
            var usage = response.getMetadata().getUsage();
            return Map.of(
                    "answer", answer(response),
                    "promptTokens", usage.getPromptTokens(),
                    "cacheWriteInputTokens", String.valueOf(usage.getCacheWriteInputTokens()),
                    "cacheReadInputTokens", String.valueOf(usage.getCacheReadInputTokens()));
        });
    }

    /**
     * Extended thinking. On current Claude models it is <em>adaptive</em>: the model decides
     * whether and how much to reason, and the effort level (LOW .. MAX) steers the depth and the
     * token spend. The fixed budget from earlier versions - thinkingEnabled(budgetTokens) - is
     * rejected with a 400 by Claude Opus 5 and Sonnet 5; it still works only on older models.
     * <p>
     * Display decides whether the reasoning is returned: SUMMARIZED sends back a summary of it,
     * OMITTED (the default) returns empty thinking blocks while still spending the tokens.
     */
    @PostMapping("anthropic/thinking")
    public Map<String, Object> thinking(@RequestBody PromptRequest promptRequest) {
        return withAnthropic(model -> {
            var response = ChatClient.builder(model, observationRegistry, null, null).build()
                    .prompt(promptRequest.userPromptText())
                    .options(AnthropicChatOptions.builder()
                            .maxTokens(16000)
                            .thinkingAdaptive(ThinkingConfigAdaptive.Display.SUMMARIZED)
                            .effort(OutputConfig.Effort.HIGH))
                    .call()
                    .chatResponse();
            // thinking blocks are the generations that carry a signature
            var thinking = response.getResults().stream()
                    .map(Generation::getOutput)
                    .filter(message -> message.getMetadata().containsKey("signature"))
                    .map(message -> String.valueOf(message.getText()))
                    .toList();
            return Map.of(
                    "thinking", thinking,
                    "answer", answer(response));
        });
    }

    /**
     * A server-side tool. Unlike the @Tool methods elsewhere in this project, the web search runs
     * inside Anthropic's infrastructure: nothing is executed locally, the tool-calling loop never
     * comes back to the application, and the answer already contains the retrieved information.
     */
    @PostMapping("anthropic/web-search")
    public Map<String, Object> webSearch(@RequestBody PromptRequest promptRequest) {
        return withAnthropic(model -> {
            var response = ChatClient.builder(model, observationRegistry, null, null).build()
                    .prompt(promptRequest.userPromptText())
                    .options(AnthropicChatOptions.builder()
                            .maxTokens(16000)
                            .webSearchTool(AnthropicWebSearchTool.builder()
                                    .maxUses(3L)
                                    .build())
                            // AUTO uses Priority Tier capacity when the organization has it, STANDARD_ONLY never does
                            .serviceTier(AnthropicServiceTier.AUTO))
                    .call()
                    .chatResponse();
            return Map.of("answer", answer(response));
        });
    }

    private Map<String, Object> withAnthropic(java.util.function.Function<AnthropicChatModel, Map<String, Object>> action) {
        var model = anthropicConfigured ? anthropicChatModel.getIfAvailable() : null;
        if (model == null) {
            return Map.of("error", "Anthropic is not configured. Set ANTHROPIC_API_KEY and restart.");
        }
        return action.apply(model);
    }

    /**
     * The answer is the last Generation - any thinking blocks come before it.
     */
    private static String answer(ChatResponse response) {
        return String.valueOf(response.getResults().getLast().getOutput().getText());
    }

    @Value("classpath:books-catalog.json")
    private Resource booksCatalog;

    private String booksCatalogText() {
        try {
            return booksCatalog.getContentAsString(StandardCharsets.UTF_8);
        }
        catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    // The catalog is static, so the whole system message is an identical prefix on every call.
    // Anything that varies (a timestamp, a user id) would have to go after it, or nothing is cached.
    private static final String SYSTEM_MESSAGE = """
            You are a Spring AI training assistant and the librarian of the training library.
            Answer questions about the Spring AI framework and recommend books only from the catalog below.

            Reference notes:
            ChatModel is the low-level provider abstraction; ChatClient is the fluent facade adding
            prompt templating, structured output conversion, tool calling and the advisor chain.
            Advisors intercept the model call and can rewrite the request, transform the response or
            short-circuit the chain. Memory, RAG and tool calling are all implemented as advisors.
            ChatMemory is the retention policy and ChatMemoryRepository the storage backend; since
            2.0 a conversation id is mandatory on every request that uses memory.
            RetrievalAugmentationAdvisor implements modular RAG: query transformation, expansion,
            retrieval, joining, post-processing and augmentation are separate components.
            The ETL pipeline is built from DocumentReader, DocumentTransformer and DocumentWriter,
            each a plain java.util.function type, so the stages compose.
            Tool calling moved out of the ChatModel implementations into ToolCallingAdvisor in 2.0,
            which made execution consistent across providers and observable through the chain.
            Tool Search indexes the tool library and exposes only a search tool to the model, which
            keeps large tool sets out of the prompt.
            Observability is provided by Micrometer observations: gen_ai.* metrics and spans for
            every model call, tool invocation and vector store operation.

            Books catalog (JSON):
            {catalog}
            """;

}
