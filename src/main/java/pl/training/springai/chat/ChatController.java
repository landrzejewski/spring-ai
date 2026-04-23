package pl.training.springai.chat;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.ai.converter.MapOutputConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pl.training.springai.chat.advisor.TimestampAdvisor;
import pl.training.springai.chat.model.Book;
import pl.training.springai.chat.model.PromptRequest;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.stream.Collectors;

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

    @PostMapping("chat-extracting-data")
    public String chatExtractingData(@RequestBody PromptRequest promptRequest) {
        return chatClient.prompt()
                .user(promptRequest.userPromptText())
                .call()
                .content();
    }

    @PostMapping("chat-extracting-structured-data")
    public Book chatExtractingStructuredData(@RequestBody PromptRequest promptRequest) {
        return chatClient.prompt()
                .user(promptRequest.userPromptText())
                .call()
                .entity(Book.class);
    }

    @PostMapping("chat-extracting-structured-data-as-list")
    public List<Book> chatExtractingStructuredDataAsList(@RequestBody PromptRequest promptRequest) {
        return chatClient.prompt()
                .user(promptRequest.userPromptText())
                .call()
                .entity(new ParameterizedTypeReference<>() {});
    }

    // Map can be created using parametrized typ or using converter
    @PostMapping("chat-extracting-structured-data-as-map")
    public Map<String, Object> chatExtractingStructuredDataAsMap(@RequestBody PromptRequest promptRequest) {
        var mapConverter = new MapOutputConverter();
        var format = mapConverter.getFormat();
        System.out.println("format: " + format);
        String template = """
        Input :  {input}
        {format}
        """;
        var promptTemplate = new PromptTemplate(template);
        var message = promptTemplate.createMessage(Map.of("input", promptRequest.userPromptText(), "format", format));
        var promptMessage = new Prompt(List.of(message));
        var result = chatClient
                .prompt(promptMessage)
                .call()
                .content();
        return mapConverter.convert(result);
    }

    private final List<Message> messages = Collections.synchronizedList(new ArrayList<>());;

    @PostMapping("chat-with-conversation")
    public Flux<String> chatWithConversation(@RequestBody PromptRequest promptRequest) {
        var summary = getMessagesSummary();
        System.out.println("******************************************");
        System.out.printf(summary);
        System.out.println("******************************************");
        messages.add(new UserMessage(promptRequest.userPromptText()));
        return chatClient
                .prompt()
                .system(summary)
                .user(promptRequest.userPromptText())
                .stream()
                .content();
    }

    private String getMessagesSummary() {
        var text = messages.stream()
                .map(Message::getText)
                .collect(Collectors.joining());
        System.out.println("Text: " + text);
        if (text.isBlank()) {
            return "-";
        }
        return chatClient
                .prompt()
                .user(spec -> spec
                        .text("""
                                Summarize only the information explicitly stated in the source text, without adding 
                                any external details or interpretations. Present the most important facts in no more than 10 concise 
                                sentences. Source text: {text}""")
                        .param("text", text)
                )
                .call()
                .content();
    }

    @PostMapping("chat-with-advisors")
    public String chatWithAdvisors(@RequestBody PromptRequest promptRequest) {
        return chatClient
                .prompt(promptRequest.userPromptText())
                .advisors(
                       /* SimpleLoggerAdvisor.builder().build(),*/
                        new TimestampAdvisor()
                )
                .call()
                .content();
    }

}
