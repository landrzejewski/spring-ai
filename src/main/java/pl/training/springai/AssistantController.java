package pl.training.springai;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.Map;

@RestController
public class AssistantController {

    private final ChatClient chatClient;
    private final PromptTemplate promptTemplate;

    public AssistantController(OllamaChatModel chatModel, PgVectorStore vectorStore,
                               @Value("classpath:prompts/prompt-template.st") Resource promptTemplateText) {
        this.chatClient = ChatClient.builder(chatModel)
                .defaultAdvisors(QuestionAnswerAdvisor.builder(vectorStore).build())
                .build();
        this.promptTemplate = new PromptTemplate(promptTemplateText);
    }

    @PostMapping(value = "questions")
    public Flux<String> trainings(@RequestBody String questionText) {
        var promptText = promptTemplate
                .create(Map.of("questionText", questionText))
                .getContents();
        return chatClient
                .prompt(promptText)
                .stream()
                .content();
    }

}
