package pl.training.controllers.extras;

import com.anthropic.models.messages.ThinkingConfigEnabled;
import org.springframework.ai.anthropic.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.training.model.PromptRequest;

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
 * endpoints report that instead of failing at startup.
 */
@RestController
@RequestMapping("providers")
public class ProvidersController {

    private final OpenAiChatModel openAiChatModel;
    private final OllamaChatModel ollamaChatModel;
    private final ObjectProvider<AnthropicChatModel> anthropicChatModel;
    private final boolean anthropicConfigured;

    public ProvidersController(OpenAiChatModel openAiChatModel,
                               OllamaChatModel ollamaChatModel,
                               ObjectProvider<AnthropicChatModel> anthropicChatModel,
                               @Value("${spring.ai.anthropic.api-key:}") String anthropicApiKey) {
        this.openAiChatModel = openAiChatModel;
        this.ollamaChatModel = ollamaChatModel;
        this.anthropicChatModel = anthropicChatModel;
        this.anthropicConfigured = anthropicApiKey != null && !anthropicApiKey.isBlank();
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

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
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
        var response = ChatClient.builder(model).build()
                .prompt(promptRequest.userPromptText())
                .call()
                .chatResponse();
        var usage = response.getMetadata().getUsage();
        return Map.of(
                "model", String.valueOf(response.getMetadata().getModel()),
                "answer", response.getResult().getOutput().getText(),
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
     * cacheReadInputTokens in the usage metadata - the second call should read from the cache.
     */
    @PostMapping("anthropic/prompt-caching")
    public Map<String, Object> promptCaching(@RequestBody PromptRequest promptRequest) {
        return withAnthropic(model -> {
            var response = ChatClient.builder(model).build()
                    .prompt()
                    .system(LONG_SYSTEM_MESSAGE)
                    .user(promptRequest.userPromptText())
                    .options(AnthropicChatOptions.builder()
                            .cacheOptions(AnthropicCacheOptions.builder()
                                    .strategy(AnthropicCacheStrategy.SYSTEM_ONLY)
                                    .build()))
                    .call()
                    .chatResponse();
            return Map.of(
                    "answer", response.getResult().getOutput().getText(),
                    // The native usage carries the cache counters that the portable Usage does not
                    "usage", String.valueOf(response.getMetadata().getUsage().getNativeUsage()));
        });
    }

    /**
     * Extended thinking. The model is given a token budget to reason before answering, and Display
     * decides whether the reasoning is returned: SUMMARIZED sends back a summary of it, OMITTED
     * hides it entirely while still spending the budget.
     */
    @PostMapping("anthropic/thinking")
    public Map<String, Object> thinking(@RequestBody PromptRequest promptRequest) {
        return withAnthropic(model -> {
            var response = ChatClient.builder(model).build()
                    .prompt(promptRequest.userPromptText())
                    .options(AnthropicChatOptions.builder()
                            .maxTokens(8000)
                            .thinkingEnabled(4000, ThinkingConfigEnabled.Display.SUMMARIZED))
                    .call()
                    .chatResponse();
            return Map.of("answer", response.getResult().getOutput().getText());
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
            var response = ChatClient.builder(model).build()
                    .prompt(promptRequest.userPromptText())
                    .options(AnthropicChatOptions.builder()
                            .maxTokens(4096)
                            .webSearchTool(AnthropicWebSearchTool.builder()
                                    .maxUses(3L)
                                    .build())
                            // STANDARD_ONLY refuses the cheaper, best-effort capacity tier
                            .serviceTier(AnthropicServiceTier.AUTO))
                    .call()
                    .chatResponse();
            return Map.of("answer", response.getResult().getOutput().getText());
        });
    }

    private Map<String, Object> withAnthropic(java.util.function.Function<AnthropicChatModel, Map<String, Object>> action) {
        var model = anthropicConfigured ? anthropicChatModel.getIfAvailable() : null;
        if (model == null) {
            return Map.of("error", "Anthropic is not configured. Set ANTHROPIC_API_KEY and restart.");
        }
        return action.apply(model);
    }

    private static final String LONG_SYSTEM_MESSAGE = """
            You are a Spring AI training assistant. Answer strictly about the Spring AI framework.

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
            """;

}
