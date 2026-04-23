package pl.training.springai.chat;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

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

}
