package pl.training.controllers;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import pl.training.model.PromptRequest;
import reactor.core.publisher.Flux;

import java.util.Arrays;
import java.util.List;

@RestController
public class McpController {

    private final ObjectProvider<ToolCallbackProvider>  toolCallbackProvider;
    private final ChatClient chatClient;

    public McpController(ObjectProvider<ToolCallbackProvider> toolCallbackProvider, ChatClient chatClient) {
        this.toolCallbackProvider = toolCallbackProvider;
        this.chatClient = chatClient;
    }

    @PostMapping("mcp-tools")
    public List<String> mcpTools() {
        return toolCallbackProvider.stream()
                .flatMap(provider -> Arrays.stream(provider.getToolCallbacks()))
                .map(ToolCallback::getToolDefinition)
                .map(definition -> definition.name() + " - " + definition.description())
                .toList();
    }

    @PostMapping("mcp-client/{userId}")
    public Flux<String> mcpClient(@RequestBody PromptRequest promptRequest, @PathVariable String userId) {
        var providers = toolCallbackProvider.stream().toList();
        return chatClient.prompt()
                .user(promptRequest.userPromptText())
                .tools(providers)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, userId))
                .stream()
                .content();
    }

}
