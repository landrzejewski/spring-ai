package pl.training.springai.chat;

import org.springframework.ai.audio.transcription.AudioTranscriptionPrompt;
import org.springframework.ai.audio.transcription.TranscriptionModel;
import org.springframework.ai.audio.tts.TextToSpeechModel;
import org.springframework.ai.audio.tts.TextToSpeechOptions;
import org.springframework.ai.audio.tts.TextToSpeechPrompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import pl.training.springai.chat.model.PromptRequest;

@RestController
public class AudioController {

    private final TextToSpeechModel textToSpeechModel;
    private final TranscriptionModel transcriptionModel;

    public AudioController(TextToSpeechModel textToSpeechModel, TranscriptionModel transcriptionModel) {
        this.textToSpeechModel = textToSpeechModel;
        this.transcriptionModel = transcriptionModel;
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
        // var voice = OpenAiAudioApi.SpeechRequest.Voice.ALLOY;
        // var format = OpenAiAudioApi.SpeechRequest.AudioResponseFormat.MP3;
        var options = TextToSpeechOptions.builder()
                .model("tts-1-hd")
                .voice("alloy")
                .format("mp3")
                .speed(1.0)
                .build();
        var prompt = new TextToSpeechPrompt(promptRequest.userPromptText(), options);
        var bytes = textToSpeechModel.call(prompt)
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
        return transcriptionModel
                .call(prompt)
                .getResult()
                .getOutput();
    }

}
