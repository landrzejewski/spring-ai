package pl.training.advisors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.SystemMessage;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Logs what actually goes to the model and what comes back.
 * <p>
 * This is a teaching implementation - it shows where the data lives in the API. In production the
 * same information is available out of the box: Spring AI publishes Micrometer observations for
 * every ChatClient, ChatModel, tool call and vector store operation, including the gen_ai.*
 * metrics and spans. Enable spring.ai.chat.observations.log-prompt / log-completion instead of
 * writing an advisor like this one.
 * <p>
 * Worth noting where the interesting data sits:
 * <ul>
 *   <li>{@code ChatClientRequest.prompt()} - the final list of Messages, after every earlier
 *       advisor (memory, RAG, guardrails) has had its say;</li>
 *   <li>{@code ChatResponse.getMetadata()} - the model id and the Usage record with the token
 *       counts. Since Spring AI 2.0 the usage reported for a tool-calling exchange is cumulative
 *       across all the internal round trips of the loop, not just the last one.</li>
 * </ul>
 */
public class ObservabilityAdvisor implements CallAdvisor, StreamAdvisor {

    private static final Logger logger = LoggerFactory.getLogger(ObservabilityAdvisor.class);

    @Override
    public String getName() {
        return ObservabilityAdvisor.class.getSimpleName();
    }

    @Override
    public int getOrder() {
        return 0;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        var startTime = System.currentTimeMillis();
        logRequest(request);
        var response = chain.nextCall(request);
        var durationMs = System.currentTimeMillis() - startTime;
        logResponse(response, durationMs);
        return response;
    }

    /**
     * The streaming counterpart. There is no single response object to inspect, so the metadata is
     * taken from the last chunk that carries it and logged once the Flux terminates.
     */
    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        var startTime = System.currentTimeMillis();
        logRequest(request);
        var lastWithUsage = new AtomicReference<ChatClientResponse>();
        return chain.nextStream(request)
                .doOnNext(chunk -> {
                    var chatResponse = chunk.chatResponse();
                    if (chatResponse != null && chatResponse.getMetadata() != null
                            && chatResponse.getMetadata().getUsage() != null) {
                        lastWithUsage.set(chunk);
                    }
                })
                .doFinally(signal -> {
                    var durationMs = System.currentTimeMillis() - startTime;
                    logger.info("========== LLM STREAM FINISHED ({}) ==========", signal);
                    var last = lastWithUsage.get();
                    if (last != null) {
                        logMetadata(last);
                    }
                    logger.info("Duration: {} ms", durationMs);
                });
    }

    private void logRequest(ChatClientRequest request) {
        logger.info("========== LLM REQUEST ==========");
        // getContents() renders every message of the prompt as plain text
        var userMessage = request.prompt().getContents();
        logger.info("User message: {}", userMessage);

        // getInstructions() is the raw message list - roles included
        request.prompt().getInstructions().stream()
                .filter(msg -> msg instanceof SystemMessage)
                .findFirst()
                .ifPresent(msg -> logger.info("System message: {}", msg.getText()));
        logger.info("=================================");
    }

    private void logResponse(ChatClientResponse response, long durationMs) {
        logger.info("========== LLM RESPONSE ==========");
        var chatResponse = response.chatResponse();
        if (chatResponse != null && chatResponse.getResult() != null) {
            // a ChatResponse holds a list of Generations; getResult() is the first one
            var content = chatResponse.getResult().getOutput().getText();
            logger.info("Content: {}", content);
            logMetadata(response);
        }
        logger.info("Duration: {} ms", durationMs);
        logger.info("==================================");
    }

    private void logMetadata(ChatClientResponse response) {
        var metadata = response.chatResponse().getMetadata();
        if (metadata == null) {
            return;
        }
        logger.info("Model: {}", metadata.getModel());
        var usage = metadata.getUsage();
        if (usage != null) {
            logger.info("Tokens: prompt={}, completion={}, total={}",
                    usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens());
        }
    }

}
