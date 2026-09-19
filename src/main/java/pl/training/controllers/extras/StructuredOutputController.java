package pl.training.controllers.extras;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.*;
import org.springframework.ai.util.JacksonUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.*;
import pl.training.springai.model.PromptRequest;
import pl.training.springai.model.SentimentResult;
import pl.training.springai.model.SummaryResult;
import pl.training.springai.model.TranslationResult;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Structured output turns a free-text completion into a typed Java object. Spring AI offers three
 * increasingly reliable mechanisms, all reachable from the same {@code entity()} call:
 * <ol>
 *   <li><b>Prompt-based</b> (the default) - a JSON schema is derived from the target type and
 *       appended to the prompt as format instructions. Works with every provider, but the model
 *       is only <em>asked</em> to comply.</li>
 *   <li><b>Provider-native</b> - {@code useProviderStructuredOutput()} sends the schema through
 *       the provider's own constrained-decoding API (OpenAI response_format), so malformed JSON
 *       becomes impossible rather than unlikely. Only available where the provider supports it.</li>
 *   <li><b>Validated with self-correction</b> - {@code validateSchema()} checks the reply against
 *       the schema and, on a mismatch, sends the errors back to the model and retries.</li>
 * </ol>
 */
@RestController
@RequestMapping("structured")
public class StructuredOutputController {

    private final ChatClient chatClient;
    private final ChatClient ollamaChatClient;

    public StructuredOutputController(ChatClient chatClient,
                                      @Qualifier("ollamaChatClient") ChatClient ollamaChatClient) {
        this.chatClient = chatClient;
        this.ollamaChatClient = ollamaChatClient;
    }

    /**
     * The baseline. BeanOutputConverter reflects over the record, generates a JSON schema and
     * appends it to the prompt; the reply is then deserialized into the record.
     */
    @PostMapping("sentiment")
    public SentimentResult sentiment(@RequestBody PromptRequest promptRequest) {
        return chatClient.prompt()
                .user(spec -> spec
                        .text("Analyse the sentiment of the following text: {text}")
                        .param("text", promptRequest.userPromptText()))
                .call()
                .entity(SentimentResult.class);
    }

    /**
     * The same request, but the schema is enforced by the provider instead of merely requested in
     * the prompt. The model is constrained at decoding time, so the reply cannot be invalid JSON
     * and no format instructions are added to the prompt - which also saves tokens.
     */
    @PostMapping("sentiment-native")
    public SentimentResult sentimentNative(@RequestBody PromptRequest promptRequest) {
        return chatClient.prompt()
                .user(spec -> spec
                        .text("Analyse the sentiment of the following text: {text}")
                        .param("text", promptRequest.userPromptText()))
                .call()
                .entity(SentimentResult.class, ChatClient.EntityParamSpec::useProviderStructuredOutput);
    }

    /**
     * Self-correction, plus the cleanup step that has to come first.
     * <p>
     * {@code validateSchema()} installs StructuredOutputValidationAdvisor: it checks the reply
     * against the schema and, on a mismatch, sends the validation errors back to the model and
     * asks again. It cannot help with a reply that is not JSON at all - a ```json fence fails at
     * parse time, before validation - which is what ResponseTextCleaner is for.
     * <p>
     * Defaults to the local Ollama model, because that is where both problems actually occur;
     * against a strong provider neither ever fires. Pass {@code ?provider=openai} to see the same
     * code succeed on the first attempt.
     * <p>
     * A caveat worth seeing on a small model: the mechanism can work and the answer still be
     * useless. Bielik-1.5B returns well-formed JSON with null fields here - schema validation is
     * about shape, not about substance.
     */
    @PostMapping("summary-validated")
    public SummaryResult summaryValidated(@RequestBody PromptRequest promptRequest,
                                          @RequestParam(defaultValue = "ollama") String provider) {
        // A ResponseTextCleaner runs before parsing. MarkdownCodeBlockCleaner strips the ```json
        // fence that small models like to wrap their answer in; ThinkingTagCleaner removes the
        // <think> blocks reasoning models emit. Neither is applied by default.
        var cleaner = CompositeResponseTextCleaner.builder()
                .addCleaner(new MarkdownCodeBlockCleaner())
                .addCleaner(new ThinkingTagCleaner())
                .addCleaner(new WhitespaceCleaner())
                .build();
        var converter = new BeanOutputConverter<>(SummaryResult.class,
                JacksonUtils.getDefaultJsonMapper(), cleaner);

        return client(provider).prompt()
                .user(spec -> spec
                        .text("Summarize the following text: {text}")
                        .param("text", promptRequest.userPromptText()))
                .call()
                .entity(converter, ChatClient.EntityParamSpec::validateSchema);
    }

    /**
     * ListOutputConverter targets a plain {@code List<String>} - a comma-separated format rather
     * than JSON, which is cheaper and easier for weak models than a full schema.
     * <p>
     * For a list of records use {@code entity(new ParameterizedTypeReference<List<Book>>() {})}
     * instead; the generic type has to be captured because it is erased at runtime.
     */
    @PostMapping("keywords")
    public List<String> keywords(@RequestBody PromptRequest promptRequest) {
        var converter = new ListOutputConverter();
        return chatClient.prompt()
                .user(spec -> spec
                        .text("Extract the key topics from the following text: {text}")
                        .param("text", promptRequest.userPromptText()))
                .call()
                .entity(converter);
    }

    /**
     * The escape hatch: a StructuredOutputConverter written from scratch. The interface has two
     * responsibilities - getFormat() supplies the instructions injected into the prompt, convert()
     * parses the reply.
     * <p>
     * For the fence-stripping shown here the built-in MarkdownCodeBlockCleaner is the better
     * answer (see summaryValidated above). Write a converter only when the output format itself is
     * not JSON, or when the parsing needs logic no cleaner can express.
     */
    @PostMapping("translation-lenient")
    public TranslationResult translationLenient(@RequestBody PromptRequest promptRequest,
                                                @RequestParam(defaultValue = "ollama") String provider) {
        return client(provider).prompt()
                .user(spec -> spec
                        .text("Translate the following text into English: {text}")
                        .param("text", promptRequest.userPromptText()))
                .call()
                .entity(new LenientJsonOutputConverter<>(TranslationResult.class));
    }

    /**
     * Shows what entity() hides: the schema that gets appended to the prompt. Useful when a model
     * keeps producing the wrong shape and you need to see what it was actually told.
     */
    @PostMapping("schema")
    public Map<String, String> schema() {
        var converter = new BeanOutputConverter<>(SummaryResult.class);
        return Map.of(
                "jsonSchema", converter.getJsonSchema(),
                "promptInstructions", converter.getFormat()
        );
    }

    private ChatClient client(String provider) {
        return "openai".equals(provider) ? chatClient : ollamaChatClient;
    }

    static class LenientJsonOutputConverter<T> implements StructuredOutputConverter<T> {

        private static final Pattern FENCE = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```");

        private final BeanOutputConverter<T> delegate;

        LenientJsonOutputConverter(Class<T> targetType) {
            this.delegate = new BeanOutputConverter<>(targetType);
        }

        @Override
        public String getFormat() {
            return delegate.getFormat();
        }

        @Override
        public String getJsonSchema() {
            return delegate.getJsonSchema();
        }

        @Override
        public T convert(String source) {
            var matcher = FENCE.matcher(source);
            var json = matcher.find() ? matcher.group(1).trim() : source.trim();
            return delegate.convert(json);
        }

    }

}
