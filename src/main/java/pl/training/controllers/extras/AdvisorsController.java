package pl.training.controllers.extras;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SafeGuardAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.VectorStoreChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.web.bind.annotation.*;
import pl.training.advisors.CanaryWordAdvisor;
import pl.training.advisors.ObservabilityAdvisor;
import pl.training.advisors.SafetyAdvisor;
import pl.training.advisors.TimestampAdvisor;
import pl.training.model.PromptRequest;
import pl.training.tools.DateTimeTool;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private final ObservationRegistry observationRegistry;

    public AdvisorsController(OpenAiChatModel chatModel,
                              OllamaChatModel ollamaChatModel,
                              SimpleVectorStore booksVectorStore,
                              EmbeddingModel embeddingModel,
                              ObservationRegistry observationRegistry) {
        this.chatModel = chatModel;
        this.ollamaChatModel = ollamaChatModel;
        this.booksVectorStore = booksVectorStore;
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

}
