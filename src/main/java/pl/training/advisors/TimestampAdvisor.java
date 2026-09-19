package pl.training.advisors;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.List;

public class TimestampAdvisor implements CallAdvisor, StreamAdvisor {

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
        // nextCall() passes control to the next advisor, and eventually to the model itself
        var response = callAdvisorChain.nextCall(chatClientRequest);
        var originalText = response.chatResponse().getResult().getOutput().getText();
        var modifiedText = originalText + "\n Response timestamp: " + Instant.now();
        var assistantMessage = new AssistantMessage(modifiedText);
        var newResponse = ChatResponse.builder()
                .generations(List.of(new Generation(assistantMessage)))
                .build();
        // mutate() copies the response, keeping the advisor context, and swaps the ChatResponse
        return response.mutate().chatResponse(newResponse).build();
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest, StreamAdvisorChain chain) {
        // The chunks are forwarded untouched and one synthetic chunk is appended at the end.
        // Rewriting the text in place is not possible here - each element carries only a fragment.
        return chain.nextStream(chatClientRequest)
                .concatWith(Flux.defer(() -> Flux.just(timestampChunk())));
    }

    private ChatClientResponse timestampChunk() {
        var assistantMessage = new AssistantMessage("\n Response timestamp: " + Instant.now());
        return ChatClientResponse.builder()
                .chatResponse(ChatResponse.builder()
                        .generations(List.of(new Generation(assistantMessage)))
                        .build())
                .build();
    }

    @Override
    public String getName() {
        return TimestampAdvisor.class.getSimpleName();
    }

    /**
     * Advisors run in ascending order. Ordering matters: a memory advisor has to run before the
     * model call to inject the history, while a guardrail has to run early enough to block the
     * request. Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER marks the slot reserved for memory.
     */
    @Override
    public int getOrder() {
        return 0;
    }

}
