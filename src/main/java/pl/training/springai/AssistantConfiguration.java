package pl.training.springai;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.preretrieval.query.transformation.CompressionQueryTransformer;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.util.List;
import java.util.stream.Collectors;

@Configuration
@EnableConfigurationProperties(RagProperties.class)
public class AssistantConfiguration {

    @Value("classpath:prompts/assistant-system-prompt.st")
    private Resource systemPrompt;

    @Value("classpath:prompts/query-rewrite.st")
    private Resource queryRewritePrompt;

    @Value("classpath:prompts/context-augmentation.st")
    private Resource contextAugmentationPrompt;

    @Value("classpath:prompts/empty-context.st")
    private Resource emptyContextPrompt;

    /**
     * ChatMemory is the retention policy layered on top of a ChatMemoryRepository - here the
     * JdbcChatMemoryRepository the JDBC starter auto-configures, backed by the SPRING_AI_CHAT_MEMORY
     * table. MessageWindowChatMemory keeps only the last N messages, which bounds the prompt size
     * no matter how long the conversation runs. It is declared only to set that N; the
     * auto-configured default keeps 20.
     */
    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository, RagProperties properties) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(properties.maxHistoryMessages())
                .build();
    }

    /**
     * Retrieval Augmented Generation assembled from the modular RAG components. The advisor runs
     * a fixed sequence of replaceable stages before the model is ever called:
     * <pre>
     *   QueryTransformer  -&gt;  DocumentRetriever  -&gt;  DocumentJoiner  -&gt;  QueryAugmenter  -&gt;  model
     * </pre>
     * <ul>
     *   <li><b>QueryTransformer</b> - the question is rarely a good search query. A follow-up such
     *       as "and how is that different from field injection?" carries almost no signal, and a
     *       broad one such as "how do I use Spring Data?" lands on tables of contents and
     *       dependency lists. CompressionQueryTransformer is the only built-in transformer that
     *       reads Query.history() (RewriteQueryTransformer sees the query text alone), so it gets a
     *       custom template that also rewrites the question into a concrete English search query -
     *       one extra model call per question covers both problems. Only the search uses the
     *       rewritten query; the model still answers the original question;</li>
     *   <li><b>VectorStoreDocumentRetriever</b> runs the similarity search. topK caps how many
     *       chunks come back, similarityThreshold discards the ones that only look relevant
     *       because nothing better was available. The embedding model is multilingual, so a
     *       question in Polish finds the English manuals without a TranslationQueryTransformer;</li>
     *   <li><b>ContextualQueryAugmenter</b> builds the final prompt from the question and the
     *       retrieved chunks. Its default documentFormatter joins bare Document texts, so the model
     *       would have nothing to cite - the formatter below labels every excerpt with its source.
     *       The default promptTemplate tells the model to say it does not know whenever the answer
     *       is not in the context, which turns every broad question into a refusal; the custom one
     *       asks for a partial answer instead. It also places the answer-language rule right
     *       before the question: the same rule in the system prompt made gemma4 answer English
     *       questions in French. allowEmptyContext(false) still makes the assistant
     *       decline when retrieval came back empty, using emptyContextPromptTemplate in place of
     *       the question.</li>
     * </ul>
     * The ChatClient.Builder handed to the transformer is a separate, advisor-free instance - a
     * builder carrying this advisor would make the rewrite step recurse into RAG itself.
     */
    @Bean
    public Advisor retrievalAugmentationAdvisor(VectorStore vectorStore, ChatClient.Builder chatClientBuilder,
                                                RagProperties properties) {
        return RetrievalAugmentationAdvisor.builder()
                .queryTransformers(CompressionQueryTransformer.builder()
                        .chatClientBuilder(chatClientBuilder)
                        .promptTemplate(new PromptTemplate(queryRewritePrompt))
                        .build())
                .documentRetriever(VectorStoreDocumentRetriever.builder()
                        .vectorStore(vectorStore)
                        .topK(properties.topK())
                        .similarityThreshold(properties.similarityThreshold())
                        .build())
                .queryAugmenter(ContextualQueryAugmenter.builder()
                        .promptTemplate(new PromptTemplate(contextAugmentationPrompt))
                        .emptyContextPromptTemplate(new PromptTemplate(emptyContextPrompt))
                        .documentFormatter(AssistantConfiguration::formatExcerpts)
                        .allowEmptyContext(false)
                        .build())
                .build();
    }

    /**
     * The assistant itself. Both capabilities are advisors, ordered by getOrder() rather than by
     * the order they are listed in: MessageChatMemoryAdvisor (HIGHEST_PRECEDENCE + 200) runs before
     * RetrievalAugmentationAdvisor (0). So the history is already in the prompt when the RAG
     * advisor rewrites the question, and the memory stores the question as asked - not the
     * prompt stuffed with retrieved chunks.
     * <p>
     * defaultSystem(Resource) renders the file through the configured TemplateRenderer, which is
     * StringTemplate by default - curly braces in the prompt file would be parsed as placeholders
     * and fail, which is why the system prompt is kept free of them.
     */
    @Bean
    public ChatClient assistantChatClient(ChatClient.Builder chatClientBuilder, ChatMemory chatMemory,
                                          Advisor retrievalAugmentationAdvisor) {
        return chatClientBuilder
                .defaultSystem(systemPrompt)
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        retrievalAugmentationAdvisor)
                .build();
    }

    private static String formatExcerpts(List<Document> documents) {
        return documents.stream()
                .map(document -> "[Source: %s, page %s]%n%s".formatted(
                        document.getMetadata().get(PagePdfDocumentReader.METADATA_FILE_NAME),
                        AssistantController.pageOf(document),
                        document.getText()))
                .collect(Collectors.joining("\n\n---\n\n"));
    }

}
