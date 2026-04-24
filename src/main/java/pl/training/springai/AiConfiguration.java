package pl.training.springai;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepositoryDialect;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.reader.JsonReader;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.Resource;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.List;

@Configuration
public class AiConfiguration {

    @Bean
    public ChatClient ollamaChatClient(OllamaChatModel ollamaChatModel) {
        return ChatClient.create(ollamaChatModel);
    }

    // MessageChatMemoryAdvisor: dodaje osobne obiekty Message z rolami
    // {
    //   "messages": [
    //     {"role": "user", "content": "Cześć, jestem Jan"},
    //     {"role": "assistant", "content": "Miło Cię poznać!"},
    //     {"role": "user", "content": "Kim jestem?"}
    //   ]
    // }
    //
    // PromptChatMemoryAdvisor: wstrzykuje jako tekst w system message
    // {
    //   "messages": [
    //     {"role": "system", "content": "MEMORY:\nUSER: Cześć, jestem Jan\nASSISTANT: Miło Cię poznać!"},
    //     {"role": "user", "content": "Kim jestem?"}
    //   ]
    // }
    //

    @Primary
    @Bean
    public ChatClient openAiChatClient(OpenAiChatModel openAiChatModel, ChatMemory chatMemory) {
        return ChatClient.builder(openAiChatModel)
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build()
                        // PromptChatMemoryAdvisor.builder(chatMemory).build()
                )
                // .defaultToolCallbacks()
                // .defaultOptions()
                .build();
    }

    @Bean
    public ChatMemory chatMemory(JdbcChatMemoryRepository jdbcChatMemoryRepository) {
        return MessageWindowChatMemory.builder()
                .maxMessages(10)
                .chatMemoryRepository(jdbcChatMemoryRepository)
                .build();
    }

    @Bean
    public JdbcChatMemoryRepository jdbcChatMemoryRepository(DataSource dataSource) {
        return JdbcChatMemoryRepository.builder()
                .dataSource(dataSource)
                .dialect(JdbcChatMemoryRepositoryDialect.from(dataSource))
                .build();
    }

    @Value("classpath:books-catalog.json")
    private Resource booksCatalog;

    @Primary
    @Bean
    public EmbeddingModel embeddingModel(OpenAiEmbeddingModel embeddingModel) {
        return embeddingModel;
    }

    @Bean
    public SimpleVectorStore simpleVectorStore(EmbeddingModel embeddingModel) {
        var vectorStore = SimpleVectorStore.builder(embeddingModel).build();
        var documents = createDocumentsWithMetadata();
        vectorStore.add(documents);
        return vectorStore;
    }

    public void initPgvector(PgVectorStore vectorStore) {
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
