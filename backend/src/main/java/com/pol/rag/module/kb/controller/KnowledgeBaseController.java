package com.pol.rag.module.kb.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pol.rag.common.api.PageResult;
import com.pol.rag.common.api.Result;
import com.pol.rag.common.api.ResultCode;
import com.pol.rag.common.exception.BusinessException;
import com.pol.rag.config.properties.AppProperties;
import com.pol.rag.module.kb.entity.*;
import com.pol.rag.module.kb.mapper.*;
import com.pol.rag.module.kb.service.*;
import com.pol.rag.security.SecurityUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

/**
 * 知识库管理控制器（管理员权限）。
 *
 * <p>提供文档上传/列表/删除/重新处理、分类管理、标签管理等 REST 接口。
 * 全部接口受 RBAC 保护（{@code ADMIN} 角色）。</p>
 *
 * <p>文档上传流程：接收文件 → 保存至本地绝对路径 → 写入 kb_document 表（状态 PARSING）
 * → 异步触发 {@link DocumentIngestionService#ingestAsync} 完成解析、分块、向量化。</p>
 */
@Tag(name = "知识库管理(管理员)")
@RestController
@RequestMapping("/api/kb")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KbDocumentMapper kbDocumentMapper;
    private final KbDocumentTagMapper kbDocumentTagMapper;
    private final KbChunkMapper kbChunkMapper;
    private final CategoryService categoryService;
    private final TagService tagService;
    private final DocumentIngestionService ingestionService;
    private final AppProperties appProperties;

    // ────────── Document management ──────────

    /**
     * 上传文档：保存到本地存储 → 插入 kb_document（状态 PARSING）→ 触发异步摄取。
     *
     * <p>存储路径使用绝对路径解析，避免 Tomcat 临时工作目录漂移导致文件丢失。</p>
     */
    @Operation(summary = "上传文档")
    @PostMapping("/document/upload")
    public Result<Void> upload(@RequestParam("file") MultipartFile file,
                               @RequestParam(value = "title", required = false) String title,
                               @RequestParam(value = "categoryId", required = false) Long categoryId) {
        if (!ingestionService.isSupported(file.getOriginalFilename())) {
            throw new BusinessException(ResultCode.DOC_TYPE_UNSUPPORTED);
        }

        // Resolve storage root to absolute path (avoid Tomcat temp-dir CWD issues)
        Path storageRoot = Paths.get(appProperties.getStorage().getRootPath()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(storageRoot);
        } catch (IOException e) {
            throw new BusinessException(ResultCode.DOC_UPLOAD_FAILED, "创建存储目录失败");
        }

        String fileName = file.getOriginalFilename();
        // Strip path components to prevent directory traversal (e.g. "../../etc/passwd" → "passwd")
        String safeName = fileName != null ? Paths.get(fileName).getFileName().toString() : "unknown";
        String storedName = UUID.randomUUID() + "_" + safeName;
        Path destPath = storageRoot.resolve(storedName);
        try {
            file.transferTo(destPath.toFile());
        } catch (IOException e) {
            throw new BusinessException(ResultCode.DOC_UPLOAD_FAILED, e.getMessage());
        }

        String fileType = "";
        if (fileName != null && fileName.contains(".")) {
            fileType = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
        }

        KbDocument doc = new KbDocument();
        doc.setTitle(title != null ? title : fileName);
        doc.setCategoryId(categoryId);
        doc.setFileName(fileName);
        doc.setFileType(fileType);
        doc.setFileSize(file.getSize());
        doc.setStoragePath(destPath.toAbsolutePath().normalize().toString());
        doc.setStatus("PARSING");
        doc.setUploadedBy(SecurityUtil.getCurrentUserId());
        kbDocumentMapper.insert(doc);

        // Trigger async ingestion
        ingestionService.ingestAsync(doc.getId(), destPath.toString());

        return Result.success();
    }

    /**
     * 查询文档列表，支持按标题模糊搜索和分页。
     */
    @Operation(summary = "文档列表")
    @GetMapping("/document/list")
    public Result<PageResult<KbDocument>> listDocs(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String keyword) {
        LambdaQueryWrapper<KbDocument> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            wrapper.like(KbDocument::getTitle, keyword);
        }
        wrapper.orderByDesc(KbDocument::getCreatedAt);
        Page<KbDocument> p = kbDocumentMapper.selectPage(new Page<>(page, size), wrapper);
        return Result.success(PageResult.of(p));
    }

    /**
     * 删除文档：清理磁盘文件（忽略异常）、删除关联 MySQL 分块记录。
     *
     * <p><b>注意：</b>当前实现不删除 Milvus 中的向量数据（保持问答可追溯），
     * 这是有意为之的简化设计，而非 bug。</p>
     */
    @Operation(summary = "删除文档")
    @DeleteMapping("/document/{id}")
    public Result<Void> deleteDoc(@PathVariable Long id) {
        KbDocument doc = kbDocumentMapper.selectById(id);
        if (doc == null) throw new BusinessException(ResultCode.DOC_NOT_FOUND);
        // Delete file
        try { Files.deleteIfExists(Paths.get(doc.getStoragePath())); } catch (IOException ignored) {}
        // Delete chunks (MySQL, Milvus not needed—vectors stay for simplicity)
        kbChunkMapper.delete(new LambdaQueryWrapper<KbChunk>().eq(KbChunk::getDocumentId, id));
        kbDocumentMapper.deleteById(id);
        return Result.success();
    }

    @Operation(summary = "文档详情")
    @GetMapping("/document/{id}")
    public Result<KbDocument> getDoc(@PathVariable Long id) {
        KbDocument doc = kbDocumentMapper.selectById(id);
        if (doc == null) throw new BusinessException(ResultCode.DOC_NOT_FOUND);
        return Result.success(doc);
    }

    @Operation(summary = "重新处理文档")
    @PostMapping("/document/{id}/reprocess")
    public Result<Void> reprocess(@PathVariable Long id) {
        KbDocument doc = kbDocumentMapper.selectById(id);
        if (doc == null) throw new BusinessException(ResultCode.DOC_NOT_FOUND);
        doc.setStatus("PARSING");
        doc.setErrorMsg(null);
        kbDocumentMapper.updateById(doc);
        ingestionService.ingestAsync(doc.getId(), doc.getStoragePath());
        return Result.success();
    }

    // ────────── Category management ──────────

    @Operation(summary = "分类列表")
    @GetMapping("/category/list")
    public Result<List<KbCategory>> listCategories(@RequestParam(required = false) Long parentId) {
        return Result.success(categoryService.listByParent(parentId));
    }

    @Operation(summary = "新增分类")
    @PostMapping("/category")
    public Result<Void> addCategory(@RequestBody KbCategory category) {
        categoryService.save(category);
        return Result.success();
    }

    @Operation(summary = "更新分类")
    @PutMapping("/category")
    public Result<Void> updateCategory(@RequestBody KbCategory category) {
        categoryService.updateById(category);
        return Result.success();
    }

    @Operation(summary = "删除分类")
    @DeleteMapping("/category/{id}")
    public Result<Void> deleteCategory(@PathVariable Long id) {
        categoryService.removeById(id);
        return Result.success();
    }

    // ────────── Tag management ──────────

    @Operation(summary = "标签列表")
    @GetMapping("/tag/list")
    public Result<List<KbTag>> listTags() {
        return Result.success(tagService.list());
    }

    @Operation(summary = "新增标签")
    @PostMapping("/tag")
    public Result<Void> addTag(@RequestBody KbTag tag) {
        tagService.save(tag);
        return Result.success();
    }

    @Operation(summary = "删除标签")
    @DeleteMapping("/tag/{id}")
    public Result<Void> deleteTag(@PathVariable Long id) {
        tagService.removeById(id);
        return Result.success();
    }
}
