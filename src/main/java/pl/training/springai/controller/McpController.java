package pl.training.springai.controller;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import pl.training.springai.model.PromptRequest;
import reactor.core.publisher.Flux;

@RestController
public class McpController {

    private final ChatClient chatClient;
    private final ToolCallbackProvider toolCallbackProvider;

    public McpController(ChatClient chatClient, ToolCallbackProvider toolCallbackProvider) {
        this.chatClient = chatClient;
        this.toolCallbackProvider = toolCallbackProvider;
    }

    @PostMapping("mcp-client")
    public Flux<String> mcpClient(@RequestBody PromptRequest promptRequest) {
        return chatClient.prompt()
                .user(promptRequest.userPromptText())
                .toolCallbacks(toolCallbackProvider)
                .stream()
                .content();
    }

}
