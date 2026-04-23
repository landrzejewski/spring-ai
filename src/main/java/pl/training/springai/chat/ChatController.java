package pl.training.springai.chat;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pl.training.springai.chat.model.PromptRequest;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
public class ChatController {

    private final ChatClient chatClient;

    public ChatController(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @PostMapping("chat")
    public String chat(@RequestBody String promptText) {
        return chatClient.prompt(promptText)
                .call()
                .content();
    }

    @PostMapping("chat-with-stream")
    public Flux<String> chatWithStream(@RequestBody String promptText) {
        return chatClient.prompt(promptText)
                .stream()
                .content()
                .map(String::toLowerCase);
    }

    @PostMapping("chat-with-parametrized-prompt")
    public Flux<String> chatWithParametrizedPrompt(@RequestBody String topic) {
        return chatClient.prompt()
                .user(spec -> spec
                        .text("Tell me a joke about {topic}")
                        .param("topic", topic)
                )
                .stream()
                .content();
    }

    // A system message in LLMs is a special type of input that provides high-level instructions, context, or behavioral
    // guidelines to the model before it processes user queries. Think of it as the "behind-the-scenes"
    // instructions that shape how the AI should respond.
    // Use it as a guide or a restriction to the model's behavior

    @Value("classpath:prompts/dev-assistant.st")
    private Resource devAssistantSystemMessage;

    @PostMapping("chat-with-roles-and-external-template")
    public Flux<String> chatWithRolesAndExternalTemplate(
            @RequestBody PromptRequest promptRequest,
            @RequestParam(defaultValue = "Java") String programmingLanguage) {
        var userMessage = new UserMessage(promptRequest.userPromptText());

        Map<String, Object> params = Map.of("programming_language", programmingLanguage);
        var systemMessageTemplate = new SystemPromptTemplate(devAssistantSystemMessage);
        var systemMessage = systemMessageTemplate.createMessage(params);

        var prompt = new Prompt(List.of(userMessage, systemMessage));

        var chatOption = ChatOptions.builder()
                .temperature(0.1)
                .maxTokens(500)
                .build();

        return chatClient
                /*.prompt()
                  .user(promptRequest.userMessage())
                  .system(promptUserSpec -> promptUserSpec
                    .text(devAssistantSystemMessage)
                    .params(params)
                 )*/
                .prompt(prompt)
                .options(chatOption)
                .stream()
                .content();
    }


}
