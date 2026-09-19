package pl.training.moderation;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.moderation.*;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaChatOptions;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * ModerationModel backed by a local Ollama chat model used as an LLM-as-a-judge. Spring AI ships a
 * ModerationModel only for OpenAI, so this adapter lets ModerationService stay provider-agnostic
 * while the classification runs locally (Bielik, llama-guard, ...).
 * <p>
 * The judge answers with a single label which is mapped onto the Spring AI Categories flags that
 * ModerationService inspects.
 */
public class ModerationModel implements org.springframework.ai.moderation.ModerationModel {

    private static final String SYSTEM_PROMPT = """
            Jestes klasyfikatorem bezpieczenstwa tresci. Ocen podana tresc uzytkownika.

            Kategorie zagrozen:
            - HATE: mowa nienawisci, dyskryminacja, rasizm
            - HARASSMENT: nekanie, grozby, obrazanie konkretnych osob
            - VIOLENCE: przemoc, instrukcje przestepcze, terroryzm
            - SELF_HARM: samookaleczenie, samobojstwo
            - SEXUAL: tresci seksualne, pornograficzne

            Odpowiedz JEDNYM slowem:
            - SAFE - jesli tresc jest bezpieczna
            - nazwa kategorii (HATE, HARASSMENT, VIOLENCE, SELF_HARM lub SEXUAL) - jesli tresc jest niebezpieczna

            Odpowiedz TYLKO jednym slowem, bez zadnych dodatkowych wyjasnien.
            """;

    private static final Set<String> LABELS = Set.of("HATE", "HARASSMENT", "VIOLENCE", "SELF_HARM", "SEXUAL");

    private final OllamaChatModel chatModel;
    private final String modelName;

    public ModerationModel(OllamaChatModel chatModel, String modelName) {
        this.chatModel = chatModel;
        this.modelName = modelName;
    }

    @Override
    public ModerationResponse call(ModerationPrompt moderationPrompt) {
        var options = OllamaChatOptions.builder()
                .model(modelName)
                .temperature(0.0)
                .numPredict(20)
                .build();
        var prompt = new Prompt(
                List.of(new SystemMessage(SYSTEM_PROMPT), new UserMessage(moderationPrompt.getInstructions().getText())),
                options
        );

        var answer = chatModel.call(prompt).getResult().getOutput().getText();
        var label = LABELS.stream()
                .filter(answer.trim().toUpperCase(Locale.ROOT)::contains)
                .findFirst()
                .orElse("SAFE");

        var categories = Categories.builder()
                .hate(label.equals("HATE"))
                .harassment(label.equals("HARASSMENT"))
                .violence(label.equals("VIOLENCE"))
                .selfHarm(label.equals("SELF_HARM"))
                .sexual(label.equals("SEXUAL"))
                .build();
        var result = ModerationResult.builder()
                .flagged(!label.equals("SAFE"))
                .categories(categories)
                .build();
        var moderation = Moderation.builder()
                .id(UUID.randomUUID().toString())
                .model(modelName)
                .results(List.of(result))
                .build();
        return new ModerationResponse(new Generation(moderation));
    }

}
