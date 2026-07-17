package com.pol.rag.module.kb.service;

import com.pol.rag.common.api.ResultCode;
import com.pol.rag.common.exception.BusinessException;
import com.pol.rag.config.AsyncConfig;
import com.pol.rag.config.properties.AppProperties;
import com.pol.rag.module.kb.entity.KbChunk;
import com.pol.rag.module.kb.entity.KbDocument;
import com.pol.rag.module.kb.mapper.KbChunkMapper;
import com.pol.rag.module.kb.mapper.KbDocumentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.FileSystemResource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentIngestionService {

    private final KbDocumentMapper kbDocumentMapper;
    private final KbChunkMapper kbChunkMapper;
    private final VectorStore vectorStore;
    private final AppProperties appProperties;

    private static final List<String> SUPPORTED_EXTENSIONS = List.of(
            "pdf", "docx", "doc", "txt", "md", "markdown", "csv", "json", "xml", "html", "htm");

    public boolean isSupported(String fileName) {
        if (fileName == null || !fileName.contains(".")) return false;
        String ext = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
        return SUPPORTED_EXTENSIONS.contains(ext);
    }

    @Async(AsyncConfig.DOC_EXECUTOR)
    public void ingestAsync(Long documentId, String filePath) {
        try {
            ingest(documentId, filePath);
        } catch (Exception e) {
            log.error("Document ingestion failed for docId={}", documentId, e);
            KbDocument doc = new KbDocument();
            doc.setId(documentId);
            doc.setStatus("FAILED");
            doc.setErrorMsg(e.getMessage());
            kbDocumentMapper.updateById(doc);
        }
    }

    private void ingest(Long documentId, String filePath) {
        log.info("Starting ingestion for docId={}, path={}", documentId, filePath);

        // 1. Parse
        TikaDocumentReader reader = new TikaDocumentReader(new FileSystemResource(filePath));
        List<Document> rawDocs = reader.read();
        String fullText = rawDocs.stream()
                .map(Document::getText)
                .reduce("", (a, b) -> a + "\n" + b);

        if (fullText.isBlank()) {
            throw new BusinessException(ResultCode.DOC_PARSE_FAILED, "文档内容为空");
        }
        log.info("Parsed docId={}, textLen={}", documentId, fullText.length());

        // 2. Split
        AppProperties.Rag ragProps = appProperties.getRag();
        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(ragProps.getChunkSize())
                .withMinChunkSizeChars(100)
                .withMinChunkLengthToEmbed(20)
                .withMaxNumChunks(5000)
                .withKeepSeparator(true)
                .build();

        List<Document> chunks = splitter.split(List.of(new Document(fullText)));
        log.info("Split into {} chunks for docId={}", chunks.size(), documentId);

        // 3. Build AI docs for Milvus
        List<Document> aiDocs = new ArrayList<>();
        List<KbChunk> chunkEntities = new ArrayList<>();

        for (int i = 0; i < chunks.size(); i++) {
            String chunkText = chunks.get(i).getText();
            KbChunk chunk = new KbChunk();
            chunk.setDocumentId(documentId);
            chunk.setChunkIndex(i);
            chunk.setContent(chunkText);
            chunk.setTokenCount(estimateTokens(chunkText));
            chunkEntities.add(chunk);

            Document aiDoc = new Document(chunkText, Map.of(
                    "document_id", String.valueOf(documentId),
                    "chunk_index", String.valueOf(i)));
            aiDocs.add(aiDoc);
        }

        // 4. Embed & store in Milvus via VectorStore
        // DashScope text-embedding-v3 limits to 10 inputs per batch
        log.info("Writing {} chunks to Milvus for docId={} (batched by 10)", aiDocs.size(), documentId);
        int batchSize = 10;
        for (int i = 0; i < aiDocs.size(); i += batchSize) {
            int end = Math.min(i + batchSize, aiDocs.size());
            vectorStore.add(aiDocs.subList(i, end));
            log.debug("Embedded batch {}-{} of {} for docId={}", i, end - 1, aiDocs.size(), documentId);
        }
        log.info("Milvus write complete for docId={}", documentId);

        // 5. Persist chunk metadata
        for (KbChunk chunk : chunkEntities) {
            chunk.setMilvusId(String.valueOf(chunk.getId()));
            kbChunkMapper.insert(chunk);
        }

        // 6. Update document status
        KbDocument doc = new KbDocument();
        doc.setId(documentId);
        doc.setStatus("INDEXED");
        doc.setChunkCount(chunkEntities.size());
        kbDocumentMapper.updateById(doc);

        log.info("Ingestion complete for docId={}, chunks={}", documentId, chunkEntities.size());
    }

    private int estimateTokens(String text) {
        if (text == null) return 0;
        int chineseChars = 0, otherChars = 0;
        for (char c : text.toCharArray()) {
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                chineseChars++;
            } else {
                otherChars++;
            }
        }
        return (int) Math.ceil(chineseChars * 1.3 + otherChars * 0.75);
    }
}
