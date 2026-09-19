package pl.training.springai;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tuning knobs for the retrieval pipeline, kept in one place so the effect of each one can be
 * observed by editing application.yml instead of recompiling.
 * <ul>
 *   <li><b>topK</b> - how many chunks the DocumentRetriever returns; every one of them ends up
 *       in the prompt, so this is the main lever on both answer quality and token cost;</li>
 *   <li><b>similarityThreshold</b> - the minimum score a chunk must reach to be used at all.
 *       Without it an off-topic question still retrieves topK documents, simply the least bad
 *       ones, and the model then answers confidently from irrelevant context;</li>
 *   <li><b>chunkSize</b> - measured in tokens by TokenTextSplitter, not in characters. It counts
 *       with the CL100K encoding, so for bge-m3 the size is an approximation. Changing it
 *       changes the content_hash of every file, which triggers a full re-index;</li>
 *   <li><b>maxHistoryMessages</b> - the size of the MessageWindowChatMemory window.</li>
 * </ul>
 */
@ConfigurationProperties("app.rag")
public record RagProperties(
        String documentsLocation,
        int topK,
        double similarityThreshold,
        int chunkSize,
        int maxHistoryMessages) {
}
