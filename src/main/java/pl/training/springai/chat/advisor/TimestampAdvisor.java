package pl.training.springai.chat.advisor;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.time.Instant;
import java.util.List;

public class TimestampAdvisor implements CallAdvisor {

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
        System.out.println("Sending LLM request...");
        var response = callAdvisorChain.nextCall(chatClientRequest);
        var originalText = response.chatResponse().getResult().getOutput().getText();
        var modifiedText = originalText + "\n Response timestamp: " + Instant.now();
        var assistantMessage = new AssistantMessage(modifiedText);
        var newResponse = ChatResponse.builder()
                .generations(List.of(new Generation(assistantMessage)))
                .build();
        return response.mutate().chatResponse(newResponse).build();
    }

    @Override
    public String getName() {
        return TimestampAdvisor.class.getSimpleName();
    }

    @Override
    public int getOrder() {
        return 0;
    }

}
