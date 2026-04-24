package pl.training.springai;

import jakarta.annotation.PostConstruct;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.io.IOException;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Configuration
public class AppConfiguration {

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private PgVectorStore externalVectorStore;

    private final ResourcePatternResolver resourcePatternResolver = new PathMatchingResourcePatternResolver();

    // These will hold the patterns, not the actual resources
    @Value("${app.documents.pdf-pattern:classpath:documents/*.pdf}")
    private String pdfPattern;

    @Value("${app.documents.html-pattern:classpath:documents/*.html}")
    private String htmlPattern;

    @Value("${app.documents.txt-pattern:classpath:documents/*.txt}")
    private String txtPattern;


    @Bean
    public ChatClient ollamaChatClient (OllamaChatModel ollamaChatModel) {
        return ChatClient.create(ollamaChatModel);
    }

    @PostConstruct
    public void initVectorStore() throws IOException {
        System.out.println("Initializing vector store...");

        // Get already embedded document hashes
        Set<String> embeddedDocuments = getEmbeddedDocumentHashes();
        System.out.println("Found " + embeddedDocuments.size() + " already embedded documents");

        List<Document> allDocuments = new ArrayList<>();

        // Process PDF files
        try {
            Resource[] pdfResources = resourcePatternResolver.getResources(pdfPattern);
            if (pdfResources.length > 0) {
                System.out.println("Found " + pdfResources.length + " PDF files matching pattern: " + pdfPattern);
                allDocuments.addAll(processPdfDocuments(pdfResources, embeddedDocuments));
            } else {
                System.out.println("No PDF files found matching pattern: " + pdfPattern);
            }
        } catch (IOException e) {
            System.err.println("Error loading PDF resources: " + e.getMessage());
        }

        // Process HTML files
        try {
            Resource[] htmlResources = resourcePatternResolver.getResources(htmlPattern);
            if (htmlResources.length > 0) {
                System.out.println("Found " + htmlResources.length + " HTML files matching pattern: " + htmlPattern);
                allDocuments.addAll(processHtmlDocuments(htmlResources, embeddedDocuments));
            } else {
                System.out.println("No HTML files found matching pattern: " + htmlPattern);
            }
        } catch (IOException e) {
            System.err.println("Error loading HTML resources: " + e.getMessage());
        }

        // Process TXT files
        try {
            Resource[] txtResources = resourcePatternResolver.getResources(txtPattern);
            if (txtResources.length > 0) {
                System.out.println("Found " + txtResources.length + " TXT files matching pattern: " + txtPattern);
                allDocuments.addAll(processTextDocuments(txtResources, embeddedDocuments));
            } else {
                System.out.println("No TXT files found matching pattern: " + txtPattern);
            }
        } catch (IOException e) {
            System.err.println("Error loading TXT resources: " + e.getMessage());
        }

        if (!allDocuments.isEmpty()) {
            // Process documents in smaller batches to avoid token limit issues
            int batchSize = 10;
            int totalIndexed = 0;

            for (int i = 0; i < allDocuments.size(); i += batchSize) {
                int end = Math.min(i + batchSize, allDocuments.size());
                List<Document> batch = allDocuments.subList(i, end);

                try {
                    externalVectorStore.accept(batch);
                    totalIndexed += batch.size();
                    System.out.println("Indexed batch: " + batch.size() + " documents (Total: " + totalIndexed + "/" + allDocuments.size() + ")");
                } catch (Exception e) {
                    System.err.println("Failed to index batch starting at index " + i + ": " + e.getMessage());
                    // Try to process documents individually
                    for (Document doc : batch) {
                        try {
                            // Further truncate if needed
                            String text = doc.getText();
                            if (text.length() > 2000) {
                                Document truncatedDoc = new Document(text.substring(0, 2000), doc.getMetadata());
                                externalVectorStore.accept(List.of(truncatedDoc));
                                totalIndexed++;
                            } else {
                                externalVectorStore.accept(List.of(doc));
                                totalIndexed++;
                            }
                        } catch (Exception docError) {
                            System.err.println("Failed to index document: " + doc.getMetadata().get("source") + " - " + docError.getMessage());
                        }
                    }
                }
            }
            System.out.println("Successfully indexed " + totalIndexed + " document chunks out of " + allDocuments.size());
        } else {
            System.out.println("No documents found to index");
        }
    }

    private Set<String> getEmbeddedDocumentHashes() {
        Set<String> hashes = new HashSet<>();
        try {
            // Query the vector store metadata to get all document hashes
            var sql = """
                SELECT DISTINCT metadata->>'document_hash' as hash
                FROM vector_store
                WHERE metadata->>'document_hash' IS NOT NULL
            """;

            jdbcClient.sql(sql)
                    .query((rs, rowNum) -> rs.getString("hash"))
                    .stream()
                    .forEach(hashes::add);
        } catch (Exception e) {
            System.err.println("Error retrieving embedded document hashes: " + e.getMessage());
        }
        return hashes;
    }

    private String computeDocumentHash(Resource resource) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            // Include filename and file size in hash
            String identifier = resource.getFilename() + ":" + resource.contentLength();
            digest.update(identifier.getBytes());

            // Convert to hex string
            byte[] hashBytes = digest.digest();
            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            System.err.println("Error computing document hash: " + e.getMessage());
            return "";
        }
    }

    private List<Document> processPdfDocuments(Resource[] pdfDocuments, Set<String> embeddedDocuments) {
        List<Document> allPdfDocuments = new ArrayList<>();

        var config = PdfDocumentReaderConfig.builder()
                .withPagesPerDocument(1)
                .withPageExtractedTextFormatter(ExtractedTextFormatter.builder().build())
                .build();

        // Reduced chunk size to avoid token limit issues
        var textSplitter = TokenTextSplitter.builder()
                .withChunkSize(200)
                .withMinChunkSizeChars(40)
                .withMinChunkLengthToEmbed(20)
                .withMaxNumChunks(2)
                .withKeepSeparator(true)
                .build();

        for (Resource pdfResource : pdfDocuments) {
            try {
                // Check if document is already embedded
                String documentHash = computeDocumentHash(pdfResource);
                if (embeddedDocuments.contains(documentHash)) {
                    System.out.println("Skipping already embedded PDF: " + pdfResource.getFilename());
                    continue;
                }
                var pdfReader = new PagePdfDocumentReader(pdfResource, config);
                var documents = pdfReader.get();

                documents.forEach(doc -> {
                    doc.getMetadata().put("source", pdfResource.getFilename());
                    doc.getMetadata().put("file_type", "pdf");
                    doc.getMetadata().put("indexed_date", LocalDateTime.now().toString());
                    doc.getMetadata().put("document_hash", documentHash);
                });

                try {
                    allPdfDocuments.addAll(textSplitter.apply(documents));
                    System.out.println("Processed PDF: " + pdfResource.getFilename());
                } catch (Exception splitError) {
                    System.err.println("Error splitting PDF " + pdfResource.getFilename() + ": " + splitError.getMessage());
                    // Process documents one by one if batch fails
                    for (Document doc : documents) {
                        try {
                            allPdfDocuments.addAll(textSplitter.apply(List.of(doc)));
                        } catch (Exception e) {
                            System.err.println("Skipping oversized page in PDF: " + pdfResource.getFilename());
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("Error processing PDF " + pdfResource.getFilename() + ": " + e.getMessage());
            }
        }

        return allPdfDocuments;
    }

    private List<Document> processHtmlDocuments(Resource[] htmlDocuments, Set<String> embeddedDocuments) throws IOException {
        List<Document> allHtmlDocuments = new ArrayList<>();
        // Use smaller chunks for HTML files which tend to be larger
        var textSplitter = TokenTextSplitter.builder()
                .withChunkSize(150)
                .withMinChunkSizeChars(30)
                .withMinChunkLengthToEmbed(15)
                .withMaxNumChunks(2)
                .withKeepSeparator(true)
                .build();

        for (Resource htmlResource : htmlDocuments) {
            try {
                // Check if document is already embedded
                String documentHash = computeDocumentHash(htmlResource);
                if (embeddedDocuments.contains(documentHash)) {
                    System.out.println("Skipping already embedded HTML: " + htmlResource.getFilename());
                    continue;
                }
                // Use TextReader for HTML files
                var textReader = new TextReader(htmlResource);
                var documents = textReader.get();

                // Check if document content is too large
                for (Document doc : documents) {
                    String content = doc.getText();
                    Document processDoc = doc;

                    if (content.length() > 10000) { // Limit content size
                        System.out.println("HTML file too large, truncating: " + htmlResource.getFilename());
                        processDoc = new Document(content.substring(0, 10000), doc.getMetadata());
                        content = processDoc.getText();
                    }

                    processDoc.getMetadata().put("source", htmlResource.getFilename());
                    processDoc.getMetadata().put("file_type", "html");
                    processDoc.getMetadata().put("indexed_date", LocalDateTime.now().toString());
                    processDoc.getMetadata().put("document_hash", documentHash);

                    try {
                        // Split each document individually to handle errors
                        List<Document> splitDocs = textSplitter.apply(List.of(processDoc));
                        allHtmlDocuments.addAll(splitDocs);
                    } catch (Exception splitError) {
                        System.err.println("Error splitting HTML document " + htmlResource.getFilename() + ": " + splitError.getMessage());
                        // Add truncated version if splitting fails
                        String truncatedContent = content.length() > 2000 ? content.substring(0, 2000) : content;
                        Document truncatedDoc = new Document(truncatedContent, processDoc.getMetadata());
                        allHtmlDocuments.add(truncatedDoc);
                    }
                }

                System.out.println("Processed HTML: " + htmlResource.getFilename());
            } catch (Exception e) {
                System.err.println("Error processing HTML " + htmlResource.getFilename() + ": " + e.getMessage());
            }
        }

        return allHtmlDocuments;
    }

    private List<Document> processTextDocuments(Resource[] txtDocuments, Set<String> embeddedDocuments) throws IOException {
        List<Document> allTextDocuments = new ArrayList<>();
        // Reduced chunk size to avoid token limit issues
        var textSplitter = TokenTextSplitter.builder()
                .withChunkSize(200)
                .withMinChunkSizeChars(40)
                .withMinChunkLengthToEmbed(20)
                .withMaxNumChunks(2)
                .withKeepSeparator(true)
                .build();

        for (Resource txtResource : txtDocuments) {
            try {
                // Check if document is already embedded
                String documentHash = computeDocumentHash(txtResource);
                if (embeddedDocuments.contains(documentHash)) {
                    System.out.println("Skipping already embedded TXT: " + txtResource.getFilename());
                    continue;
                }
                var textReader = new TextReader(txtResource);
                var documents = textReader.get();

                documents.forEach(doc -> {
                    doc.getMetadata().put("source", txtResource.getFilename());
                    doc.getMetadata().put("file_type", "txt");
                    doc.getMetadata().put("indexed_date", LocalDateTime.now().toString());
                    doc.getMetadata().put("document_hash", documentHash);
                });

                try {
                    allTextDocuments.addAll(textSplitter.apply(documents));
                    System.out.println("Processed TXT: " + txtResource.getFilename());
                } catch (Exception splitError) {
                    System.err.println("Error splitting TXT " + txtResource.getFilename() + ": " + splitError.getMessage());
                    for (Document doc : documents) {
                        String content = doc.getText();
                        Document processDoc = doc;
                        if (content.length() > 5000) {
                            content = content.substring(0, 5000);
                            processDoc = new Document(content, doc.getMetadata());
                        }
                        allTextDocuments.add(processDoc);
                    }
                }
            } catch (Exception e) {
                System.err.println("Error processing TXT " + txtResource.getFilename() + ": " + e.getMessage());
            }
        }

        return allTextDocuments;
    }

}
