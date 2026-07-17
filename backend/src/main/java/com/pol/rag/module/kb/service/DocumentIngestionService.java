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

/**
 * 文档摄取管道：解析 → 分块 → 向量嵌入 → 写入 Milvus + 持久化元数据。
 *
 * <p>摄取流程（{@link #ingestAsync} 异步触发）：</p>
 * <ol>
 *   <li>{@link TikaDocumentReader} 解析文档全文</li>
 *   <li>{@link TokenTextSplitter} 按 chunkSize 切分为片段</li>
 *   <li>为每个片段构建 Milvus Document（含 metadata）</li>
 *   <li>通过 {@link VectorStore#add} 写入 Milvus 向量库（每批最多 10 条，
 *       避免 DashScope text-embedding-v3 的 batch size 限制）</li>
 *   <li>片段元数据持久化到 MySQL（kb_chunk 表）</li>
 *   <li>更新 kb_document 状态为 INDEXED</li>
 * </ol>
 *
 * <p>失败处理：任何环节异常均将文档状态设为 FAILED 并记录错误信息。</p>
 */
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

    /**
     * 判断文件扩展名是否为支持的格式。
     *
     * @param fileName 文件名（如 {@code report.pdf}）
     * @return {@code true} 如果扩展名在 {@link #SUPPORTED_EXTENSIONS} 中
     */
    public boolean isSupported(String fileName) {
        if (fileName == null || !fileName.contains(".")) return false;
        String ext = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
        return SUPPORTED_EXTENSIONS.contains(ext);
    }

    /**
     * 异步触发文档摄取（由 {@link KnowledgeBaseController#upload} 调用）。
     *
     * <p>运行在 {@link AsyncConfig#DOC_EXECUTOR} 线程池上。
     * 成功后更新文档状态为 INDEXED，失败则设为 FAILED。</p>
     *
     * @param documentId 文档 ID
     * @param filePath   文档在磁盘上的绝对路径
     */
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

    /**
     * 完整摄取管道（同步执行），由 {@link #ingestAsync} 异步调用。
     *
     * @param documentId 文档 ID
     * @param filePath   文档在磁盘上的绝对路径
     * @throws BusinessException 文档内容为空时抛出（{@link ResultCode#DOC_PARSE_FAILED}）
     */
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

    /**
     * 估算文本的 token 数量。
     *
     * <p>中文按 1.3 token/字、其他按 0.75 token/字估算，
     * 经验值对标 DeepSeek tokenizer 的中英文编码效率差异。</p>
     *
     * @param text 待估算的文本
     * @return 估算 token 数
     */
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
