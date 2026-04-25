package pl.training.springai.controller;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import pl.training.springai.model.PromptRequest;

import java.util.List;
import java.util.Map;

@RestController
public class PromptsController {

    private final ChatClient chatClient;

    public PromptsController(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Value("classpath:/prompts/few-shot.st")
    private Resource fewShot;

    @Value("classpath:/prompts/multi-step.st")
    private Resource multiStep;

    @Value("classpath:/prompts/travel-prompt.st")
    private Resource travel;

    @PostMapping("zero-shot")
    public String zeroShot(@RequestBody PromptRequest promptRequest) {
        return chatClient
                .prompt()
                .user(promptRequest.userPromptText())
                .call()
                .content();
    }

    @PostMapping("few-shot")
    public String fewShot(@RequestBody PromptRequest promptRequest) {
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
        var prompt = new Prompt(List.of(systemMessage, new UserMessage(promptRequest.userPromptText())));
        return chatClient
                .prompt(prompt)
                .call()
                .content();
    }

    @PostMapping("multi-step")
    public String multiStep(@RequestBody PromptRequest promptRequest) {
        var promptTemplate = new PromptTemplate(multiStep);
        var message = promptTemplate.createMessage(Map.of("input", promptRequest.userPromptText()));
        var prompt = new Prompt(List.of(message));
        return chatClient
                .prompt(prompt)
                .call()
                .content();
    }

    @PostMapping("travel-assistant")
    public String roleAndContext(@RequestBody PromptRequest promptRequest) {
        var systemMessage = """
                You are an expert travel advisor with in-depth knowledge of destinations around the world, including
                cultural sites, accommodations, and travel arrangements. Suggest improved lodging options that are especially suitable for families.
                """;
        var promptTemplate = new PromptTemplate(travel);
        var message = promptTemplate.createMessage(Map.of("context", promptRequest.context(), "input", promptRequest.userPromptText()));
        var prompt = new Prompt(new SystemMessage(systemMessage) // role
                , message);
        return chatClient
                .prompt(prompt)
                .call()
                .content();
    }

}
