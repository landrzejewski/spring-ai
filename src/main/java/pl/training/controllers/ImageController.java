package pl.training.controllers;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImageOptionsBuilder;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import pl.training.model.PromptRequest;

import java.util.Base64;

@RestController
public class ImageController {

    private final ImageModel imageModel;
    private final ChatClient chatClient;

    public ImageController(ImageModel imageModel, OpenAiChatModel chatModel, ObservationRegistry observationRegistry) {
        this.imageModel = imageModel;
        // no memory - describing an image is a one-off request, and the primary ChatClient's
        // memory advisor would require a conversation id
        this.chatClient = ChatClient.builder(chatModel, observationRegistry, null, null).build();
    }

    /**
     * Image generation goes through ImageModel, a separate abstraction from ChatModel: it takes an
     * ImagePrompt and returns images rather than text. ImageOptionsBuilder is the portable options
     * builder, so the same code works across providers - only the values are provider-specific.
     * ResponseFormat decides what the result carries: "url" gives a link that expires, "b64_json"
     * embeds the bytes in the response - which is why getB64Json() is read here instead of getUrl().
     */

    @PostMapping("image-generation")
    public ResponseEntity<byte[]> imageGeneration(@RequestBody PromptRequest promptRequest) {
        var options = ImageOptionsBuilder.builder()
                .model("gpt-image-2.5-flare")
                .width(1024)
                .height(1024)
                //.responseFormat("url")
                .build();
        var prompt = new ImagePrompt(promptRequest.userPromptText(), options);
        var image = imageModel.call(prompt)
                .getResult()
                .getOutput()
                //.getUrl()
                .getB64Json();
        var bytes = Base64.getDecoder().decode(image);
        return ResponseEntity
                .ok()
                .header(HttpHeaders.CONTENT_TYPE, "image/png")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"image.png\"")
                .body(bytes);
    }

    @Value("classpath:image.jpg")
    private Resource image;

    @PostMapping("image-description")
    public String imageDescription(@RequestBody PromptRequest promptRequest) {
        return chatClient
                .prompt()
                .user(spec -> spec
                        .text(promptRequest.userPromptText())
                        .media(MediaType.IMAGE_JPEG, image)
                )
                // the default model (qwen3-30b-a3b) is text-only - image input needs a multimodal one
                .options(ChatOptions.builder().model("gemma-4-31b"))
                .call()
                .content();
    }

}
