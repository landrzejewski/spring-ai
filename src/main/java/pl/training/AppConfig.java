package pl.training;

import io.micrometer.observation.ObservationRegistry;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepositoryDialect;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.mcp.customizer.McpClientCustomizer;
import org.springframework.ai.moderation.ModerationModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.reader.JsonReader;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.Resource;
import pl.training.advisors.SafetyAdvisor;

import javax.sql.DataSource;
import java.net.http.HttpRequest;
import java.util.HashMap;
import java.util.List;

@Configuration
public class AppConfig {

    @Primary
    @Bean
    public ChatClient openAiChatClient(OpenAiChatModel chatModel, ChatMemory  chatMemory, ObservationRegistry observationRegistry) {
        // ChatClient.builder(chatModel) uzywa ObservationRegistry.NOOP - bez rejestru nie ma spanow/metryk ChatClient i advisorow
        return ChatClient.builder(chatModel, observationRegistry, null, null)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                //.defaultOptions()
                //.defaultTools()
                .build();
    }

    @Bean
    public ChatClient ollamaChatClient(OllamaChatModel chatModel, ObservationRegistry observationRegistry) {
        return ChatClient.builder(chatModel, observationRegistry, null, null)
                .build();
    }

    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository repository) {
        //  VectorStoreChatMemoryAdvisor()
        return MessageWindowChatMemory.builder()
                .maxMessages(10)
                .chatMemoryRepository(repository)
                .build();
    }

    @Primary
    @Bean
    public ChatMemoryRepository chatMemoryRepository(DataSource dataSource) {
         // return new InMemoryChatMemoryRepository();
        return JdbcChatMemoryRepository.builder()
                .dataSource(dataSource)
                .dialect(JdbcChatMemoryRepositoryDialect.from(dataSource))
                .build();
    }

    // @Primary, bo autokonfiguracja OpenAI i tak tworzy OpenAiModerationModel (warunek dotyczy tylko jej typu),
    // a proxy litellm nie obsluguje endpointu /moderations
    @Primary
    @Bean
    public ModerationModel moderationModel(OllamaChatModel chatModel) {
        return new pl.training.moderation.ModerationModel(chatModel, "speakleash/bielik-11b-v2.3-instruct:Q4_K_M");
    }

    @Bean
    public McpClientCustomizer<HttpClientStreamableHttpTransport.Builder> mcpSearchBearerToken(@Value("${mcp-search.token:}") String token) {
        return (name, builder) -> {
            if ("mcp-search".equals(name)) {
                if (!token.isBlank()) {
                    builder.requestBuilder(HttpRequest.newBuilder().header("Authorization", "Bearer " + token));
                }
            }
        };
    }

    @Value("classpath:books-catalog.json")
    private Resource booksCatalog;

    @Bean
    public SimpleVectorStore simpleVectorStore(OllamaEmbeddingModel embeddingModel) {
        var vectorStore = SimpleVectorStore.builder(embeddingModel).build();
        var documents = createDocumentsWithMetadata();
        vectorStore.add(documents);
        return vectorStore;
    }

    @Primary
    @Bean
    public EmbeddingModel embeddingModel(OllamaEmbeddingModel embeddingModel) {
        return embeddingModel;
    }

    public void initPgVector(PgVectorStore vectorStore) {
        var documents = createDocumentsWithMetadata();
        vectorStore.add(documents);
    }

    private List<Document> createDocumentsWithMetadata() {
        var jsonReader = new JsonReader(booksCatalog, "title", "author", "genre", "year", "description");
        List<Document> rawDocuments = jsonReader.get();
        return rawDocuments.stream()
                .map(doc -> {
                    String content = doc.getText();
                    var metadata = new HashMap<String, Object>();
                    for (String line : content.split("\n")) {
                        if (line.startsWith("title: ")) {
                            metadata.put("title", line.substring(7));
                        } else if (line.startsWith("author: ")) {
                            metadata.put("author", line.substring(8));
                        } else if (line.startsWith("genre: ")) {
                            metadata.put("genre", line.substring(7));
                        } else if (line.startsWith("year: ")) {
                            try {
                                metadata.put("year", Integer.parseInt(line.substring(6)));
                            } catch (NumberFormatException e) {
                                metadata.put("year", line.substring(6));
                            }
                        }
                    }

                    return new Document(doc.getId(), content, metadata);
                })
                .toList();
    }

}
