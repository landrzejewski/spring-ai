package pl.training.springai.chat.advisor;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaChatOptions;

import java.util.List;
import java.util.Set;

/**
 * Advisor bezpieczenstwa sprawdzajacy tresc zapytania za pomoca modelu Bielik (Ollama).
 *
 * Dzialanie:
 * 1. Przechwytuje zapytanie uzytkownika PRZED wyslaniem do glownego LLM
 * 2. Wysyla tresc do modelu Bielik z promptem klasyfikujacym
 * 3. Bielik ocenia czy tresc jest bezpieczna (kategorie: HATE, VULGAR, SEX, CRIME, SELF_HARM)
 * 4. Jesli niebezpieczna - blokuje zapytanie i zwraca komunikat
 * 5. Jesli bezpieczna - przekazuje do nastepnego advisora/LLM
 */
public class SafetyAdvisor implements CallAdvisor {

    private static final String DEFAULT_UNSAFE_MESSAGE = "Tresc zapytania zostala uznana za niebezpieczna i zostala zablokowana.";
    private static final String DEFAULT_MODEL = "SpeakLeash/bielik-1.5b-v3.0-instruct";
    private static final Set<String> THREAT_CATEGORIES = Set.of("HATE", "VULGAR", "SEX", "CRIME", "SELF_HARM");

    private static final String SYSTEM_PROMPT = """
            Jestes klasyfikatorem bezpieczenstwa tresci. Twoim zadaniem jest ocena czy podana tresc jest bezpieczna.

            Kategorie zagrozen:
            - HATE: mowa nienawisci, dyskryminacja, rasizm
            - VULGAR: wulgaryzmy, obsceniczne tresci
            - SEX: tresci seksualne, pornograficzne
            - CRIME: instrukcje przestepcze, przemoc, terroryzm
            - SELF_HARM: samookaleczenie, samobojstwo

            Odpowiedz JEDNYM slowem:
            - SAFE - jesli tresc jest bezpieczna
            - nazwa kategorii (HATE, VULGAR, SEX, CRIME lub SELF_HARM) - jesli tresc jest niebezpieczna

            Odpowiedz TYLKO jednym slowem, bez zadnych dodatkowych wyjasnien.
            """;

    private final OllamaChatModel ollamaChatModel;
    private final String modelName;
    private final String unsafeContentMessage;

    public SafetyAdvisor(OllamaChatModel ollamaChatModel, String modelName, String unsafeContentMessage) {
        this.ollamaChatModel = ollamaChatModel;
        this.modelName = modelName;
        this.unsafeContentMessage = unsafeContentMessage;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        var userMessage = request.prompt().getUserMessage();
        if (userMessage != null && !isSafe(userMessage.getText())) {
            return createFailureResponse(request);
        }
        return chain.nextCall(request);
    }

    private boolean isSafe(String userMessage) {
        var options = OllamaChatOptions.builder()
                .model(modelName)
                .temperature(0.1)
                .numPredict(20)
                .build();

        var prompt = new Prompt(
                List.of(
                        new org.springframework.ai.chat.messages.SystemMessage(SYSTEM_PROMPT),
                        new org.springframework.ai.chat.messages.UserMessage(userMessage)
                ),
                options
        );

        var response = ollamaChatModel.call(prompt);
        var responseText = response.getResult().getOutput().getText().trim().toUpperCase();

        return responseText.contains("SAFE") && THREAT_CATEGORIES.stream().noneMatch(responseText::contains);
    }

    private ChatClientResponse createFailureResponse(ChatClientRequest request) {
        return new ChatClientResponse(
                ChatResponse.builder()
                        .generations(List.of(new Generation(new AssistantMessage(unsafeContentMessage))))
                        .build(),
                request.context()
        );
    }

    @Override
    public String getName() {
        return getClass().getSimpleName();
    }

    @Override
    public int getOrder() {
        return 0;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private OllamaChatModel ollamaChatModel;
        private String modelName = DEFAULT_MODEL;
        private String unsafeContentMessage = DEFAULT_UNSAFE_MESSAGE;

        public Builder ollamaChatModel(OllamaChatModel ollamaChatModel) {
            this.ollamaChatModel = ollamaChatModel;
            return this;
        }

        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        public Builder unsafeContentMessage(String message) {
            this.unsafeContentMessage = message;
            return this;
        }

        public SafetyAdvisor build() {
            if (ollamaChatModel == null) {
                throw new IllegalArgumentException("OllamaChatModel is required");
            }
            return new SafetyAdvisor(ollamaChatModel, modelName, unsafeContentMessage);
        }
    }
}
