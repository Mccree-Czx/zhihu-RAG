package com.pol.rag.module.kb.service;

import com.pol.rag.config.properties.AppProperties;
import com.pol.rag.module.kb.entity.KbDocument;
import com.pol.rag.module.kb.mapper.KbChunkMapper;
import com.pol.rag.module.kb.mapper.KbDocumentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.vectorstore.VectorStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("文档摄取服务")
class DocumentIngestionServiceTest {

    @Mock private KbDocumentMapper kbDocumentMapper;
    @Mock private KbChunkMapper kbChunkMapper;
    @Mock private VectorStore vectorStore;

    private AppProperties appProperties;

    @InjectMocks
    private DocumentIngestionService ingestionService;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
    }

    @Test
    @DisplayName("支持的文件扩展名：pdf, docx, txt, md 等")
    void shouldRecognizeSupportedExtensions() {
        assertThat(ingestionService.isSupported("report.pdf")).isTrue();
        assertThat(ingestionService.isSupported("readme.md")).isTrue();
        assertThat(ingestionService.isSupported("data.csv")).isTrue();
        assertThat(ingestionService.isSupported("notes.txt")).isTrue();
        assertThat(ingestionService.isSupported("slides.pptx")).isFalse();
        assertThat(ingestionService.isSupported("image.png")).isFalse();
    }

    @Test
    @DisplayName("文件名为 null 或无扩展名时应返回 false")
    void shouldRejectNullOrNoExtension() {
        assertThat(ingestionService.isSupported(null)).isFalse();
        assertThat(ingestionService.isSupported("noextension")).isFalse();
        assertThat(ingestionService.isSupported("")).isFalse();
    }

    @Test
    @DisplayName("摄取失败时应将文档状态设为 FAILED 并记录错误信息")
    void shouldSetFailedStatusOnError() {
        // ingestAsync calls private ingest(), which will fail because
        // TikaDocumentReader tries to read a non-existent file
        ingestionService.ingestAsync(1L, "/nonexistent/path/file.pdf");

        // Wait for async to complete (run synchronously in test with short sleep)
        try { Thread.sleep(500); } catch (InterruptedException ignored) {}

        verify(kbDocumentMapper).updateById(argThat((KbDocument d) ->
                "FAILED".equals(d.getStatus()) && d.getErrorMsg() != null));
    }
}
