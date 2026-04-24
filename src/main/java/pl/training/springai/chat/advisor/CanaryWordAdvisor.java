package pl.training.springai.chat.advisor;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;
import java.util.UUID;

/**
 * Advisor wykrywajacy proby wycieku system prompt (prompt leak prevention).
 *
 * Dzialanie:
 * 1. Generuje losowy UUID (canary word)
 * 2. Dodaje go do system message jako ukryty token
 * 3. Jesli canary word pojawi sie w odpowiedzi - wykryto wyciek
 */
public class CanaryWordAdvisor implements CallAdvisor {

    private static final String DEFAULT_MESSAGE = "Canary word detected! Potential prompt leak attempt blocked.";
    private final String canaryWordFoundMessage;

    public CanaryWordAdvisor(String canaryWordFoundMessage) {
        this.canaryWordFoundMessage = canaryWordFoundMessage;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        var canaryWord = UUID.randomUUID().toString();

        var systemMessage = request.prompt().getSystemMessage();
        var originalSystemText = systemMessage != null ? systemMessage.getText() : "";
        var augmentedSystemMessage = String.format("%s [INTERNAL_TOKEN:%s]", originalSystemText, canaryWord);

        var advisedRequest = request.mutate()
                .prompt(request.prompt().augmentSystemMessage(augmentedSystemMessage))
                .build();

        var response = chain.nextCall(advisedRequest);

        var responseText = response.chatResponse().getResult().getOutput().getText();
        if (responseText != null && responseText.contains(canaryWord)) {
            return createFailureResponse(advisedRequest);
        }

        return response;
    }

    private ChatClientResponse createFailureResponse(ChatClientRequest request) {
        return new ChatClientResponse(
                ChatResponse.builder()
                        .generations(List.of(new Generation(new AssistantMessage(canaryWordFoundMessage))))
                        .build(),
                request.context()
        );
    }

    @Override
    public String getName() {
        return getClass().getSimpleName();
    }

    @Override
    public int getOrder() {
        return 0;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String canaryWordFoundMessage = DEFAULT_MESSAGE;

        public Builder canaryWordFoundMessage(String message) {
            this.canaryWordFoundMessage = message;
            return this;
        }

        public CanaryWordAdvisor build() {
            return new CanaryWordAdvisor(canaryWordFoundMessage);
        }
    }
}
