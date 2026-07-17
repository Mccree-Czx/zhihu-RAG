package com.pol.rag.module.chat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pol.rag.common.api.ResultCode;
import com.pol.rag.common.exception.BusinessException;
import com.pol.rag.config.properties.AppProperties;
import com.pol.rag.module.chat.entity.ChatMessage;
import com.pol.rag.module.chat.entity.ChatMessageSource;
import com.pol.rag.module.chat.entity.ChatSession;
import com.pol.rag.module.chat.mapper.*;
import com.pol.rag.module.kb.entity.KbChunk;
import com.pol.rag.module.kb.entity.KbDocument;
import com.pol.rag.module.kb.mapper.KbChunkMapper;
import com.pol.rag.module.kb.mapper.KbDocumentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 会话管理与 RAG（检索增强生成）问答服务。
 *
 * <p>核心职责：
 * <ul>
 *   <li>会话 CRUD（创建、列表、删除、收藏切换、消息查询）</li>
 *   <li>SSE 流式问答：Milvus 向量检索 → 构建 System Prompt（知识库内容 +
 *       历史对话）→ DeepSeek 流式生成 → 消息与来源持久化</li>
 *   <li>非流式回退（{@link #chatSimple}），用于调试或同步调用场景</li>
 * </ul>
 *
 * <p>RAG 流程（{@link #chatStream}）：<br>
 * 用户问题 → vectorStore.similaritySearch(topK) → collectSources(来源引用)
 * → buildSystemPrompt(检索片段拼入提示词) + buildHistoryPrompt(最近 N 轮对话)
 * → chatClient.prompt().stream().chatResponse() (DeepSeek 流式输出)
 * → doOnComplete(持久化回答全文与来源引用到 MySQL)</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;
    private final ChatMessageSourceMapper sourceMapper;
    private final VectorStore vectorStore;
    private final ChatClient chatClient;
    private final KbChunkMapper chunkMapper;
    private final KbDocumentMapper documentMapper;
    private final AppProperties appProperties;

    // ────────── Session management ──────────

    /**
     * 创建新会话。
     *
     * @param userId 所属用户 ID
     * @param title  会话标题，为 {@code null} 时默认「新会话」
     * @return 已持久化的会话实体（含自增 ID）
     */
    @Transactional
    public ChatSession createSession(Long userId, String title) {
        ChatSession session = new ChatSession();
        session.setUserId(userId);
        session.setTitle(title != null ? title : "新会话");
        session.setIsFavorite(0);
        sessionMapper.insert(session);
        return session;
    }

    /**
     * 列出用户的所有会话，按更新时间倒序。
     *
     * @param userId 用户 ID
     * @return 会话列表（可能为空）
     */
    public List<ChatSession> listSessions(Long userId) {
        return sessionMapper.selectList(new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getUserId, userId)
                .orderByDesc(ChatSession::getUpdatedAt));
    }

    /**
     * 删除会话及其关联消息。仅会话拥有者可操作，否则静默忽略。
     *
     * @param sessionId 会话 ID
     * @param userId    请求用户 ID（用于权限校验）
     */
    @Transactional
    public void deleteSession(Long sessionId, Long userId) {
        ChatSession session = sessionMapper.selectById(sessionId);
        if (session == null || !session.getUserId().equals(userId)) return;
        sessionMapper.deleteById(sessionId);
        messageMapper.delete(new LambdaQueryWrapper<ChatMessage>().eq(ChatMessage::getSessionId, sessionId));
    }

    /**
     * 切换会话的收藏状态（1 ↔ 0 反转）。非拥有者静默忽略。
     *
     * @param sessionId 会话 ID
     * @param userId    请求用户 ID
     */
    @Transactional
    public void toggleFavorite(Long sessionId, Long userId) {
        ChatSession session = sessionMapper.selectById(sessionId);
        if (session == null || !session.getUserId().equals(userId)) return;
        session.setIsFavorite(session.getIsFavorite() == 1 ? 0 : 1);
        sessionMapper.updateById(session);
    }

    /**
     * 获取指定会话的全部消息（校验归属后返回），按创建时间升序排列。
     *
     * @param sessionId 会话 ID
     * @param userId    请求用户 ID（用于权限校验）
     * @return 消息列表（可能为空）
     */
    public List<ChatMessage> getMessages(Long sessionId, Long userId) {
        verifySession(sessionId, userId);
        return getMessages(sessionId);
    }

    /**
     * 获取指定会话的全部消息（无归属校验，仅内部使用），按创建时间升序排列。
     *
     * @param sessionId 会话 ID
     * @return 消息列表（可能为空）
     */
    public List<ChatMessage> getMessages(Long sessionId) {
        return messageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, sessionId)
                .orderByAsc(ChatMessage::getCreatedAt));
    }

    // ────────── RAG Chat (non-streaming fallback) ──────────

    /**
     * 非流式 RAG 问答（同步阻塞，用于调试或 API 调用）。
     *
     * <p>流程：校验会话归属 → 保存用户消息 → 向量检索 + LLM 生成 →
     * 保存助手回答 → 返回完整回答文本。</p>
     *
     * @param sessionId 会话 ID
     * @param userId    用户 ID
     * @param question  用户问题
     * @return 助手回答全文
     */
    @Transactional
    public String chatSimple(Long sessionId, Long userId, String question) {
        ChatSession session = verifySession(sessionId, userId);

        // Save user message
        ChatMessage userMsg = saveMessage(sessionId, "user", question);

        // RAG retrieval + generation
        String answer = doRagQuery(sessionId, question);
        saveMessage(sessionId, "assistant", answer);

        // Update session timestamp
        sessionMapper.updateById(session);
        return answer;
    }

    // ────────── RAG Chat (SSE streaming) ──────────

    /**
     * SSE 流式 RAG 问答（打字机效果）。
     *
     * <p><b>完整链路：</b></p>
     * <ol>
     *   <li>{@link #verifySession} — 校验会话归属</li>
     *   <li>{@link VectorStore#similaritySearch} — 从 Milvus 检索 top-K 相似片段</li>
     *   <li>{@link #collectSources} — 收集来源引用（含逐条查 MySQL 获取文档标题）</li>
     *   <li>{@link #buildSystemPrompt} — 将检索片段拼入系统提示词</li>
     *   <li>{@link #buildHistoryPrompt} — 拼接近 N 轮历史对话</li>
     *   <li>{@link ChatClient#prompt} → stream → chatResponse — DeepSeek 流式输出</li>
     *   <li>{@code doOnComplete} — 持久化回答全文与来源引用到 MySQL</li>
     *   <li>{@code doOnError} — 错误时记录部分回答并标注出错</li>
     * </ol>
     *
     * <p><b>注意：</b>调用者应在 doOnComplete 触发后重新从数据库加载消息，
     * 以获取持久化后的 messageId 用于后续来源引用查询。</p>
     *
     * @param sessionId     会话 ID
     * @param userId        用户 ID
     * @param question      用户问题
     * @param answerBuffer  流式累积缓冲区（调用方传入可变 {@link StringBuilder}）
     * @param sourcesBuffer 来源引用缓冲区
     * @return 流式回答的 {@link Flux}，每个元素为一段增量文本
     */
    public Flux<String> chatStream(Long sessionId, Long userId, String question,
                                   StringBuilder answerBuffer, List<ChatMessageSource> sourcesBuffer) {
        ChatSession session = verifySession(sessionId, userId);

        // Save user message
        saveMessage(sessionId, "user", question);

        // ── Step 1: 向量检索 ──
        // 从 Milvus 中根据语义相似度检索与问题最相关的 top-K 文本片段
        List<Document> retrieved = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(question)
                        .topK(appProperties.getRag().getTopK())
                        .similarityThreshold(appProperties.getRag().getSimilarityThreshold())
                        .build());

        // ── Step 2: 收集来源引用（含文档标题回查 MySQL） ──
        // 注意：collectSources 内对每条检索结果执行 SELECT BY ID，
        // 检索片段数即为数据库查询次数（N+1）。若 topK > 10 需考虑批量查询优化。
        collectSources(retrieved, sourcesBuffer);

        // ── Step 3: 构建提示词 ──
        String systemPrompt = buildSystemPrompt(retrieved);
        String historyPrompt = buildHistoryPrompt(sessionId);

        // ── Step 4: 流式生成 ──
        // 先保存一条空内容的 assistant 消息作为占位，流式输出完成后更新内容
        ChatMessage assistantMsg = saveMessage(sessionId, "assistant", "");
        sourcesBuffer.clear();
        collectSources(retrieved, sourcesBuffer);

        // chatClient.prompt().stream() 返回 Flux<ChatResponse>，
        // 每个 onNext 携带一段增量文本，累积到 answerBuffer 并逐段推送给前端
        return chatClient.prompt(new Prompt(List.of(
                        new SystemMessage(systemPrompt),
                        new UserMessage(historyPrompt + "\n\n用户问题：" + question))))
                .stream()
                .chatResponse()
                .map(ChatResponse::getResult)
                .mapNotNull(result -> {
                    String text = result.getOutput().getText();
                    if (text != null) {
                        answerBuffer.append(text);
                    }
                    return text;
                })
                // 流式输出完成后：持久化完整回答 + 来源引用 + 更新会话时间
                .doOnComplete(() -> {
                    assistantMsg.setContent(answerBuffer.toString());
                    messageMapper.updateById(assistantMsg);
                    for (ChatMessageSource src : sourcesBuffer) {
                        src.setMessageId(assistantMsg.getId());
                        sourceMapper.insert(src);
                    }
                    sessionMapper.updateById(session);
                })
                // 流式输出出错时：保存已生成的部分内容并标注异常
                .doOnError(e -> {
                    log.error("RAG stream error, sessionId={}", sessionId, e);
                    assistantMsg.setContent(answerBuffer + "\n\n[回答生成出错]");
                    messageMapper.updateById(assistantMsg);
                })
                // 末尾追加显式结束标记（OpenAI SSE 标准 [DONE]），
                // 让前端不依赖 TCP 层的流关闭信号即可判定结束，避免代理缓冲导致的挂起
                .concatWithValues("[DONE]");
    }

    // ────────── Internal helpers ──────────

    /**
     * 校验会话存在且属于指定用户，否则抛出异常。
     *
     * @throws BusinessException 当会话不存在（{@link ResultCode#SESSION_NOT_FOUND}）或不属于该用户时
     */
    private ChatSession verifySession(Long sessionId, Long userId) {
        ChatSession session = sessionMapper.selectById(sessionId);
        if (session == null) throw new BusinessException(ResultCode.SESSION_NOT_FOUND, "会话不存在");
        if (!session.getUserId().equals(userId)) throw new BusinessException(ResultCode.FORBIDDEN, "无权访问该会话");
        return session;
    }

    private ChatMessage saveMessage(Long sessionId, String role, String content) {
        ChatMessage msg = new ChatMessage();
        msg.setSessionId(sessionId);
        msg.setRole(role);
        msg.setContent(content);
        messageMapper.insert(msg);
        return msg;
    }

    /**
     * 从检索结果构建来源引用对象，并回查 MySQL 获取文档标题。
     *
     * <p><b>性能注意：</b>对每条检索结果执行一次 {@link KbDocumentMapper#selectById}，
     * 即 N 条结果产生 N 次数据库查询。当前 topK 默认 5，影响可控；
     * 若未来增大 topK，需改为批量查询。</p>
     */
    private void collectSources(List<Document> retrieved, List<ChatMessageSource> buffer) {
        buffer.clear();
        for (int i = 0; i < retrieved.size(); i++) {
            Document doc = retrieved.get(i);
            Map<String, Object> meta = doc.getMetadata();
            // 从 Milvus 返回的 metadata 中提取 document_id 和 chunk_index
            // metadata 值可能是 String 或 Integer，统一转为 String 处理
            String docIdStr = meta.get("document_id") instanceof String s ? s : String.valueOf(meta.getOrDefault("document_id", ""));
            String chunkIdxStr = meta.get("chunk_index") instanceof String s ? s : String.valueOf(meta.getOrDefault("chunk_index", ""));

            ChatMessageSource src = new ChatMessageSource();
            try { src.setDocumentId(Long.valueOf(docIdStr)); } catch (NumberFormatException ignored) {}
            src.setSnippet(doc.getText());
            src.setScore(doc.getScore());

            // Try to load doc title
            if (src.getDocumentId() != null) {
                KbDocument kbDoc = documentMapper.selectById(src.getDocumentId());
                if (kbDoc != null) src.setDocTitle(kbDoc.getTitle());
            }
            buffer.add(src);
        }
    }

    /**
     * 同步 RAG 查询（检索 → LLM 生成 → 返回完整回答）。
     */
    private String doRagQuery(Long sessionId, String question) {
        List<Document> retrieved = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(question)
                        .topK(appProperties.getRag().getTopK())
                        .similarityThreshold(appProperties.getRag().getSimilarityThreshold())
                        .build());

        String systemPrompt = buildSystemPrompt(retrieved);
        String historyPrompt = buildHistoryPrompt(sessionId);

        return chatClient.prompt(new Prompt(List.of(
                new SystemMessage(systemPrompt),
                new UserMessage(historyPrompt + "\n\n用户问题：" + question))))
                .call()
                .content();
    }

    /**
     * 构建系统提示词：领域限定 + 引用规则 + 检索到的知识库片段。
     */
    private String buildSystemPrompt(List<Document> retrieved) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个政治经济学领域的知识库问答助手。请严格基于以下知识库内容回答用户问题。\n");
        sb.append("如果知识库中没有相关信息，请明确告知用户「知识库中暂无相关内容」。\n");
        sb.append("回答中涉及引用的部分，请在句末用 [来源片段X] 标注。\n\n");
        sb.append("=== 知识库检索内容 ===\n");
        for (int i = 0; i < retrieved.size(); i++) {
            sb.append("[").append(i + 1).append("] ").append(retrieved.get(i).getText()).append("\n\n");
        }
        return sb.toString();
    }

    /**
     * 构建历史对话提示词：取最近 N 轮对话，逆序后按时间顺序拼接。
     *
     * <p>MySQL 查询按 {@code created_at DESC} + {@code LIMIT N*2} 取最近消息，
     * 再 {@link java.util.Collections#reverse} 恢复时间升序，保证 LLM 看到连贯的上下文。</p>
     */
    private String buildHistoryPrompt(Long sessionId) {
        // LIMIT N*2：每轮对话含 user+assistant 共 2 条消息
        List<ChatMessage> history = messageMapper.selectList(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getSessionId, sessionId)
                        .orderByDesc(ChatMessage::getCreatedAt)
                        .last("LIMIT " + (appProperties.getRag().getMaxHistoryRounds() * 2)));
        if (history.isEmpty()) return "";

        StringBuilder sb = new StringBuilder("=== 对话历史 ===\n");
        // Reverse to chronological order
        java.util.Collections.reverse(history);
        for (ChatMessage msg : history) {
            String prefix = "user".equals(msg.getRole()) ? "用户" : "助手";
            sb.append(prefix).append("：").append(msg.getContent()).append("\n");
        }
        return sb.toString();
    }
}
