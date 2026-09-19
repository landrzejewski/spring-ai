package pl.training.springai;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import pl.training.springai.model.Question;
import pl.training.springai.model.Source;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.UUID;

@RestController
public class AssistantController {

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;

    public AssistantController(ChatClient chatClient, ChatMemory chatMemory) {
        this.chatClient = chatClient;
        this.chatMemory = chatMemory;
    }

    /**
     * One question, two kinds of Server-Sent Event: the sources the answer is grounded in, then
     * the answer itself as it is generated.
     * <p>
     * The stream is consumed as chatClientResponse() rather than content(), because content()
     * exposes only the text. RetrievalAugmentationAdvisor puts the documents it retrieved into
     * the advisor context under DOCUMENT_CONTEXT, and that context travels on the
     * ChatClientResponse - so reading the citations costs nothing extra, no second search.
     * <p>
     * The context is already filled in on the very first element, which is why switchOnFirst can
     * emit the sources before a single token of the answer has arrived.
     * <p>
     * CONVERSATION_ID selects which stored history MessageChatMemoryAdvisor replays. It is
     * mandatory since Spring AI 2.0 - ChatMemory.DEFAULT_CONVERSATION_ID was removed, so an
     * application can no longer accidentally merge every user into one shared conversation.
     */
    @PostMapping(value = "questions", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> ask(@RequestBody Question question) {
        var conversationId = StringUtils.hasText(question.conversationId())
                ? question.conversationId()
                : UUID.randomUUID().toString();
        return chatClient.prompt()
                .user(question.text())
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId))
                .stream()
                .chatClientResponse()
                .switchOnFirst((first, responses) -> Flux.concat(
                        Flux.just(sourcesEvent(first.hasValue() ? sourcesOf(first.get()) : List.of())),
                        responses.map(this::textOf)
                                .filter(StringUtils::hasLength)
                                .map(this::tokenEvent)));
    }

    // ChatMemory can also be driven directly, outside the advisor chain - here to start a
    // conversation over without restarting the application.
    @DeleteMapping("conversations/{conversationId}")
    public void clearConversation(@PathVariable String conversationId) {
        chatMemory.clear(conversationId);
    }

    @SuppressWarnings("unchecked")
    private List<Source> sourcesOf(ChatClientResponse response) {
        var documents = response.context().get(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT);
        if (!(documents instanceof List<?> list)) {
            return List.of();
        }
        return ((List<Document>) list).stream()
                .map(document -> new Source(
                        (String) document.getMetadata().get(PagePdfDocumentReader.METADATA_FILE_NAME),
                        pageOf(document),
                        (String) document.getMetadata().get(PdfSectionEnricher.METADATA_SECTION)))
                .distinct()
                .toList();
    }

    // pgvector stores the metadata map as JSON, so a number read back from the store is not
    // necessarily the Integer that was written into it.
    static Integer pageOf(Document document) {
        return document.getMetadata().get(PagePdfDocumentReader.METADATA_START_PAGE_NUMBER)
                instanceof Number page ? page.intValue() : null;
    }

    private String textOf(ChatClientResponse response) {
        var chatResponse = response.chatResponse();
        if (chatResponse == null || chatResponse.getResult() == null) {
            return "";
        }
        var text = chatResponse.getResult().getOutput().getText();
        return text == null ? "" : text;
    }

    private ServerSentEvent<Object> sourcesEvent(List<Source> sources) {
        return ServerSentEvent.<Object>builder().event("sources").data(sources).build();
    }

    private ServerSentEvent<Object> tokenEvent(String text) {
        return ServerSentEvent.<Object>builder().event("token").data(text).build();
    }

}
