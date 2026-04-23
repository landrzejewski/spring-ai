package pl.training.springai;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepositoryDialect;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

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

}
