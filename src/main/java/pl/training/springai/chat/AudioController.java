package pl.training.springai.chat;

import org.springframework.ai.audio.transcription.AudioTranscriptionPrompt;
import org.springframework.ai.audio.tts.TextToSpeechPrompt;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.openai.OpenAiAudioSpeechModel;
import org.springframework.ai.openai.OpenAiAudioSpeechOptions;
import org.springframework.ai.openai.OpenAiAudioTranscriptionModel;
import org.springframework.ai.openai.api.OpenAiAudioApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import pl.training.springai.chat.model.PromptRequest;

@RestController
public class AudioController {

    private final OpenAiAudioSpeechModel audioSpeechModel; //TextToSpeechModel
    private final OpenAiAudioTranscriptionModel audioTranscriptionModel; // TranscriptionModel

    public AudioController(OpenAiAudioSpeechModel audioSpeechModel, OpenAiAudioTranscriptionModel audioTranscriptionModel) {
        this.audioSpeechModel = audioSpeechModel;
        this.audioTranscriptionModel = audioTranscriptionModel;
    }

    /*
     * Opcje TTS:
     * - model: tts-1 (szybszy) lub tts-1-hd (wyzsza jakosc)
     * - voice: glos do uzycia (ALLOY, NOVA, ECHO, FABLE, ONYX, SHIMMER, itd.)
     * - responseFormat: format wyjsciowy (MP3, WAV, AAC, FLAC, OPUS, PCM)
     * - speed: predkosc mowy (0.25 - 4.0, domyslnie 1.0)
     */

    @PostMapping("generate-speech")
    public ResponseEntity<byte[]> generateSpeech(@RequestBody PromptRequest promptRequest) {
        var voice = OpenAiAudioApi.SpeechRequest.Voice.ALLOY;
        var options = OpenAiAudioSpeechOptions.builder() // TextToSpeechOptions
                .model("tts-1-hd")
                .voice(voice)
                .responseFormat(OpenAiAudioApi.SpeechRequest.AudioResponseFormat.MP3)
                .speed(1.0)
                .build();
        var prompt = new TextToSpeechPrompt(promptRequest.userPromptText(), options);
        var bytes = audioSpeechModel.call(prompt)
                .getResult()
                .getOutput();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "audio/mpeg")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"audio.mp3\"")
                .body(bytes);
    }

    @Value("classpath:audio.mp3")
    private Resource audio;

    @PostMapping("generate-transcription")
    public String generateTranscription() {
        var prompt = new AudioTranscriptionPrompt(audio);
        return audioTranscriptionModel
                .call(prompt)
                .getResult()
                .getOutput();
    }

   /* @PostMapping("generate-audi")
    public String chat() {
        var userMessage = UserMessage.builder()
                .text("Prepare text transcription")
                .media(new Media(MimeTypeUtils.APPLICATION_OCTET_STREAM, audio))
                .build();
        return chatClient.prompt(new Prompt(userMessage))
                .call()
                .content();
    }*/

}
