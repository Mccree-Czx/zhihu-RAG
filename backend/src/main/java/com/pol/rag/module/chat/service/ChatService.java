package com.pol.rag.module.chat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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

    @Transactional
    public ChatSession createSession(Long userId, String title) {
        ChatSession session = new ChatSession();
        session.setUserId(userId);
        session.setTitle(title != null ? title : "新会话");
        session.setIsFavorite(0);
        sessionMapper.insert(session);
        return session;
    }

    public List<ChatSession> listSessions(Long userId) {
        return sessionMapper.selectList(new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getUserId, userId)
                .orderByDesc(ChatSession::getUpdatedAt));
    }

    @Transactional
    public void deleteSession(Long sessionId, Long userId) {
        ChatSession session = sessionMapper.selectById(sessionId);
        if (session == null || !session.getUserId().equals(userId)) return;
        sessionMapper.deleteById(sessionId);
        messageMapper.delete(new LambdaQueryWrapper<ChatMessage>().eq(ChatMessage::getSessionId, sessionId));
    }

    @Transactional
    public void toggleFavorite(Long sessionId, Long userId) {
        ChatSession session = sessionMapper.selectById(sessionId);
        if (session == null || !session.getUserId().equals(userId)) return;
        session.setIsFavorite(session.getIsFavorite() == 1 ? 0 : 1);
        sessionMapper.updateById(session);
    }

    public List<ChatMessage> getMessages(Long sessionId) {
        return messageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, sessionId)
                .orderByAsc(ChatMessage::getCreatedAt));
    }

    // ────────── RAG Chat (non-streaming fallback) ──────────

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

    public Flux<String> chatStream(Long sessionId, Long userId, String question,
                                   StringBuilder answerBuffer, List<ChatMessageSource> sourcesBuffer) {
        ChatSession session = verifySession(sessionId, userId);

        // Save user message
        saveMessage(sessionId, "user", question);

        // Retrieve top-k relevant chunks
        List<Document> retrieved = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(question)
                        .topK(appProperties.getRag().getTopK())
                        .similarityThreshold(appProperties.getRag().getSimilarityThreshold())
                        .build());

        // Collect sources
        collectSources(retrieved, sourcesBuffer);

        // Build prompt
        String systemPrompt = buildSystemPrompt(retrieved);
        String historyPrompt = buildHistoryPrompt(sessionId);

        // Stream response from DeepSeek
        ChatMessage assistantMsg = saveMessage(sessionId, "assistant", "");
        sourcesBuffer.clear();
        collectSources(retrieved, sourcesBuffer);

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
                .doOnComplete(() -> {
                    // Persist the full answer and sources
                    assistantMsg.setContent(answerBuffer.toString());
                    messageMapper.updateById(assistantMsg);
                    for (ChatMessageSource src : sourcesBuffer) {
                        src.setMessageId(assistantMsg.getId());
                        sourceMapper.insert(src);
                    }
                    sessionMapper.updateById(session);
                })
                .doOnError(e -> {
                    log.error("RAG stream error, sessionId={}", sessionId, e);
                    assistantMsg.setContent(answerBuffer + "\n\n[回答生成出错]");
                    messageMapper.updateById(assistantMsg);
                });
    }

    // ────────── Internal helpers ──────────

    private ChatSession verifySession(Long sessionId, Long userId) {
        ChatSession session = sessionMapper.selectById(sessionId);
        if (session == null) throw new RuntimeException("SESSION_NOT_FOUND");
        if (!session.getUserId().equals(userId)) throw new RuntimeException("SESSION_FORBIDDEN");
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

    private void collectSources(List<Document> retrieved, List<ChatMessageSource> buffer) {
        buffer.clear();
        for (int i = 0; i < retrieved.size(); i++) {
            Document doc = retrieved.get(i);
            Map<String, Object> meta = doc.getMetadata();
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

    private String buildHistoryPrompt(Long sessionId) {
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
