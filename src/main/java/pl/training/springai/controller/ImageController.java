package pl.training.springai.controller;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImageOptionsBuilder;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import pl.training.springai.model.PromptRequest;

import java.util.Base64;

@RestController
public class ImageController {

    private final ImageModel imageModel;
    private final ChatClient  chatClient;

    public ImageController(ImageModel imageModel, ChatClient chatClient) {
        this.imageModel = imageModel;
        this.chatClient = chatClient;
    }

    /*
     * - model: dall-e-3 (lepszy) lub dall-e-2 (tanszy)
     * - width/height: rozmiar obrazu (1024x1024, 1024x1792, 1792x1024 dla DALL-E-3)
     * - quality: standard lub hd (wiecej detali, drozsza)
     * - style: vivid (hiperrealistyczny) lub natural (fotorealistyczny)
     */

    @PostMapping("generate-image")
    public ResponseEntity<byte[]> generateImage(@RequestBody PromptRequest promptRequest) {
        var options = ImageOptionsBuilder.builder()
                .model("dall-e-3")
                .width(1024)
                .height(1024)
                .style("natural")
                // .responseFormat("url")
                .responseFormat("b64_json")
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

    @Value("classpath:image.png")
    private Resource image;

    @PostMapping("generate-description")
    public String generateDescription(@RequestBody PromptRequest promptRequest) {
        return chatClient
                .prompt()
                .user(spec -> spec
                        .text(promptRequest.userPromptText())
                        .media(MediaType.IMAGE_PNG, image)
                )
                .call()
                .content();
    }

}
