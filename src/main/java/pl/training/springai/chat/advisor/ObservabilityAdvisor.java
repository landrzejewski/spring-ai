package pl.training.springai.chat.advisor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.SystemMessage;

/**
 * Advisor do logowania komunikacji z LLM.
 *
 * Loguje (sprawdz konsole serwera):
 * - Wiadomosc uzytkownika
 * - Wiadomosc systemowa
 * - Odpowiedz modelu
 * - Statystyki tokenow
 * - Czas wykonania
 */
public class ObservabilityAdvisor implements CallAdvisor {

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

    private void logRequest(ChatClientRequest request) {
        logger.info("========== LLM REQUEST ==========");
        var userMessage = request.prompt().getContents();
        logger.info("User message: {}", userMessage);

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
            var content = chatResponse.getResult().getOutput().getText();
            logger.info("Content: {}", content);

            var metadata = chatResponse.getMetadata();
            if (metadata != null) {
                logger.info("Model: {}", metadata.getModel());
                var usage = metadata.getUsage();
                if (usage != null) {
                    logger.info("Tokens: prompt={}, completion={}, total={}",
                            usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens());
                }
            }
        }
        logger.info("Duration: {} ms", durationMs);
        logger.info("==================================");
    }
}
