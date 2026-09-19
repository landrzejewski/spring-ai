package pl.training.controllers.extras;

import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.VectorStoreChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.web.bind.annotation.*;
import pl.training.advisors.ObservabilityAdvisor;
import pl.training.advisors.TimestampAdvisor;
import pl.training.model.PromptRequest;
import pl.training.tools.DateTimeTool;
import reactor.core.publisher.Flux;

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
    private final SimpleVectorStore vectorStore;

    public AdvisorsController(OpenAiChatModel chatModel, SimpleVectorStore vectorStore) {
        this.chatModel = chatModel;
        this.vectorStore = vectorStore;
    }

    /**
     * SimpleLoggerAdvisor is the built-in equivalent of the hand-written ObservabilityAdvisor:
     * it logs the request and the response at DEBUG level, with no custom code.
     */
    @PostMapping("logger")
    public String logger(@RequestBody PromptRequest promptRequest) {
        return ChatClient.builder(chatModel).build()
                .prompt(promptRequest.userPromptText())
                .advisors(SimpleLoggerAdvisor.builder().build())
                .call()
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
        var advisor = QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(SearchRequest.builder()
                        .topK(3)
                        .similarityThreshold(0.5)
                        .build())
                .build();
        return ChatClient.builder(chatModel).build()
                .prompt(promptRequest.userPromptText())
                .advisors(advisor)
                .call()
                .content();
    }

    /**
     * The other chat-memory strategy. MessageChatMemoryAdvisor replays the last N messages
     * verbatim; VectorStoreChatMemoryAdvisor embeds every exchange and retrieves only the
     * fragments semantically related to the current question, injecting them as text into the
     * system message.
     * <p>
     * The trade-off: the window advisor is exact but bounded by the context size, the vector
     * advisor scales to arbitrarily long histories but can miss something the model needed.
     */
    @PostMapping("vector-memory/{conversationId}")
    public String vectorMemory(@RequestBody PromptRequest promptRequest,
                               @PathVariable String conversationId) {
        var advisor = VectorStoreChatMemoryAdvisor.builder(vectorStore)
                .defaultTopK(5)
                .build();
        return ChatClient.builder(chatModel).build()
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
     */
    @GetMapping("order")
    public Map<String, Integer> order() {
        return Map.of(
                "HIGHEST_PRECEDENCE", Integer.MIN_VALUE,
                "DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER", Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER,
                "timestampAdvisor", new TimestampAdvisor().getOrder(),
                "observabilityAdvisor", new ObservabilityAdvisor().getOrder()
        );
    }

    /**
     * Tool calling is itself an advisor, auto-registered by ChatClient whenever a request may need
     * it. Disabling the auto-registration leaves the tool call unresolved: the model's request
     * comes back as-is, with no execution and no second round trip.
     * <p>
     * This is what a bare ChatModel does in Spring AI 2.0 - the loop is no longer built into the
     * model implementations. Turn it off when you want to drive the loop yourself with a
     * ToolCallingManager, or to inspect what the model actually asked for.
     */
    @PostMapping("no-tool-loop")
    public Flux<String> noToolLoop(@RequestBody PromptRequest promptRequest) {
        return ChatClient.builder(chatModel).build()
                .prompt(promptRequest.userPromptText())
                .tools(new DateTimeTool())
                .advisors(AdvisorParams.toolCallingAdvisorAutoRegister(false))
                .stream()
                .content();
    }

}
