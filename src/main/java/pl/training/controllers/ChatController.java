package pl.training.controllers;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.ai.converter.MapOutputConverter;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.*;
import pl.training.advisors.TimestampAdvisor;
import pl.training.model.Book;
import pl.training.model.DoubleValue;
import pl.training.model.PromptRequest;
import pl.training.tools.DateTimeTool;
import pl.training.tools.PowerTool;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
public class ChatController {

    // Stateless examples use a client without memory. The primary ChatClient bean carries
    // MessageChatMemoryAdvisor, which since Spring AI 2.0 requires a conversation id on every
    // request - it is used only by the endpoints that take a userId.
    private final ChatClient chatClient;
    private final ChatClient memoryChatClient;
    private final ChatMemory chatMemory;

    public ChatController(OpenAiChatModel chatModel, ChatClient memoryChatClient, ChatMemory chatMemory, ObservationRegistry observationRegistry) {
        this.chatClient = ChatClient.builder(chatModel, observationRegistry, null, null).build();
        this.memoryChatClient = memoryChatClient;
        this.chatMemory = chatMemory;
    }

    @PostMapping("chat")
    public String chat(@RequestBody String text) {
        return chatClient.prompt(text)
                .call()
                .content();
    }

    @PostMapping("chat-with-streaming")
    public Flux<String> chatWithStreaming(@RequestBody String text) {
        return chatClient.prompt(text)
                .stream()
                .content()
                .map(String::toUpperCase);
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

    @Value("classpath:prompts/programmer-assistant.st")
    private Resource assistantTemplateText;

    @PostMapping("chat-with-roles-and-external-template")
    public Flux<String> chatWithRolesAndExternalTemplate(
            @RequestBody String text,
            @RequestParam(defaultValue = "java") String programmingLanguage) {
        var userMessage = new UserMessage(text);

        Map<String, Object> params = Map.of("programming_language", programmingLanguage);
        var systemMessageTemplate = new SystemPromptTemplate(assistantTemplateText);
        var systemMessage = systemMessageTemplate.createMessage(params);

        var prompt = new Prompt(systemMessage, userMessage);

        return chatClient.prompt(prompt)
                /*.prompt()
                  .user(text)
                  .system(spec -> spec
                      .text(assistantTemplateText)
                      .params(params)
                   )*/
                .options(ChatOptions.builder()
                        .temperature(0.1)
                        .maxTokens(20_000)
                )
                .stream()
                .content();
    }

    @PostMapping("chat-with-data-extracting-prompt")
    public String chatWithDataExtractingPrompt(@RequestBody String text) {
        return chatClient.prompt(text)
                .call()
                .content();
    }

    @PostMapping("chat-with-structured-response")
    public Book chatWithStructuredResponse(@RequestBody String text) {
        return chatClient.prompt(text)
                .call()
                .entity(Book.class);
    }

    @PostMapping("chat-with-structured-list-response")
    public List<Book> chatWithStructuredListResponse(@RequestBody String text) {
        return chatClient.prompt(text)
                .call()
                .entity(new ParameterizedTypeReference<>() {});
    }

    @PostMapping("chat-with-structured-map-response")
    public Map<String, Object> chatWithStructuredMapResponse(@RequestBody String text) {
        var mapConverter = new MapOutputConverter();
        var format = mapConverter.getFormat();
        System.out.println("format: " + format);
        String template = """
        Input :  {input}
        {format}
        """;
        var promptTemplate = new PromptTemplate(template);
        var message = promptTemplate.createMessage(Map.of("input", text, "format", format));
        var promptMessage = new Prompt(List.of(message));
        var result = chatClient
                .prompt(promptMessage)
                .call()
                .content();
        return mapConverter.convert(result);
    }


    @Value("classpath:/prompts/few-shot.st")
    private Resource fewShot;

    @Value("classpath:/prompts/multi-step.st")
    private Resource multiStep;

    @Value("classpath:/prompts/travel.st")
    private Resource travel;

    @PostMapping("zero-shot")
    public Flux<String> zeroShot(@RequestBody String text) {
        return chatClient
                .prompt()
                .user(text)
                .stream()
                .content();
    }

    @PostMapping("few-shot")
    public Flux<String> fewShot(@RequestBody String text) {
        var fewShotExamples = """
            Prompt: "Absolutely thrilled with my purchase! Everything works flawlessly."
            Answer: happy

            Prompt: "Fantastic service and excellent product quality, will buy again!"
            Answer: happy

            Prompt: "The product stopped working immediately; very frustrated with this buy."
            Answer: unhappy

            Prompt: "Item came shattered due to bad packaging, completely unusable."
            Answer: unhappy
            """;
        var systemPromptTemplate = new SystemPromptTemplate(fewShot);
        var systemMessage = systemPromptTemplate.createMessage(Map.of("few_shot_prompts", fewShotExamples));
        var prompt = new Prompt(List.of(systemMessage, new UserMessage(text)));
        return chatClient
                .prompt(prompt)
                .stream()
                .content();
    }

    @PostMapping("multi-step")
    public Flux<String> multiStep(@RequestBody String text) {
        var promptTemplate = new PromptTemplate(multiStep);
        var message = promptTemplate.createMessage(Map.of("input", text));
        var prompt = new Prompt(List.of(message));
        return chatClient
                .prompt(prompt)
                .stream()
                .content();
    }

    @PostMapping("travel-assistant")
    public Flux<String> roleAndContext(@RequestBody PromptRequest promptRequest) {
        var systemMessage = """
                You are an experienced travel advisor with in-depth knowledge of destinations worldwide,
                including cultural sites, accommodation and local transport.
                Tailor every recommendation to the traveller's context (group, dates, budget, preferences).
                Be concrete and concise. Do not invent exact prices or availability; give price ranges
                and remind the user to verify current offers. Answer in the language of the request.
                """;
        var promptTemplate = new PromptTemplate(travel);
        var message = promptTemplate.createMessage(Map.of("context", promptRequest.context(), "input", promptRequest.userPromptText()));
        var prompt = new Prompt(new SystemMessage(systemMessage), message);
        return chatClient
                .prompt(prompt)
                .stream()
                .content();
    }

    @PostMapping("chat-with-advisors")
    public Flux<String> chatWithAdvisors(@RequestBody PromptRequest promptRequest) {
        return chatClient
                .prompt(promptRequest.userPromptText())
                .advisors(
                        SimpleLoggerAdvisor.builder().build(),
                        new TimestampAdvisor()
                )
                .stream()
                .content();
    }

    private final List<Message> messages = Collections.synchronizedList(new ArrayList<>());;

    @PostMapping("chat-with-conversation")
    public Flux<String> chatWithConversation(@RequestBody PromptRequest promptRequest) {
        var summary = getMessagesSummary();
        System.out.println("******************************************");
        System.out.printf(summary);
        System.out.println("\n******************************************");
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


    @PostMapping("chat-with-conversation/{userId}")
    public Flux<String> chatWithConversationId(
            @RequestBody PromptRequest promptRequest,
            @PathVariable String userId
    ) {
        return memoryChatClient.prompt()
                .user(promptRequest.userPromptText())
                .advisors(spec -> spec
                        .param(ChatMemory.CONVERSATION_ID, userId)
                )
                .stream()
                .content();
    }

    @GetMapping("get-chat-conversation/{userId}")
    public List<Map<String, String>> getChatConversation(@PathVariable String userId) {
        return chatMemory.get(userId)
                .stream()
                .map(message -> Map.of(
                        "role", message.getMessageType().name(),
                        "text", message.getText()
                ))
                .toList();
    }

    @DeleteMapping("delete-chat-conversation/{userId}")
    public void deleteChatConversation(@PathVariable String userId) {
        chatMemory.clear(userId);
    }

    @PostMapping("chat-with-tools/{userId}")
    public Flux<String> chatWithTools(@RequestBody PromptRequest  promptRequest, @PathVariable String userId) {
        // var callbacks = ToolCallbacks.from(new DateTimeTool());
        var callbacks = FunctionToolCallback.builder("power", new PowerTool())
                // .description("Calculates the square of a number (value * value)")
                .inputType(Double.class)
                .build();

        return memoryChatClient.prompt()
                .tools(new DateTimeTool(), callbacks)
                .toolContext(Map.of("userId", "12345"))
                .user(promptRequest.userPromptText())
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, userId))
                .stream()
                .content();
    }

}
