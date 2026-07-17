package com.pol.rag.module.chat.controller;

import com.pol.rag.common.api.Result;
import com.pol.rag.common.api.ResultCode;
import com.pol.rag.common.exception.BusinessException;
import com.pol.rag.common.ratelimit.RateLimitService;
import com.pol.rag.module.chat.dto.ChatRequest;
import com.pol.rag.module.chat.entity.ChatMessage;
import com.pol.rag.module.chat.entity.ChatMessageSource;
import com.pol.rag.module.chat.entity.ChatSession;
import com.pol.rag.module.chat.mapper.ChatMessageSourceMapper;
import com.pol.rag.module.chat.service.ChatService;
import com.pol.rag.security.SecurityUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

@Tag(name = "智能问答")
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final ChatMessageSourceMapper sourceMapper;
    private final RateLimitService rateLimitService;

    // ────────── Session ──────────

    @Operation(summary = "创建会话")
    @PostMapping("/session")
    public Result<ChatSession> createSession(@RequestParam(required = false) String title) {
        Long uid = SecurityUtil.getCurrentUserId();
        return Result.success(chatService.createSession(uid, title));
    }

    @Operation(summary = "会话列表")
    @GetMapping("/session/list")
    public Result<List<ChatSession>> listSessions() {
        Long uid = SecurityUtil.getCurrentUserId();
        return Result.success(chatService.listSessions(uid));
    }

    @Operation(summary = "删除会话")
    @DeleteMapping("/session/{id}")
    public Result<Void> deleteSession(@PathVariable Long id) {
        chatService.deleteSession(id, SecurityUtil.getCurrentUserId());
        return Result.success();
    }

    @Operation(summary = "切换收藏")
    @PostMapping("/session/{id}/favorite")
    public Result<Void> toggleFavorite(@PathVariable Long id) {
        chatService.toggleFavorite(id, SecurityUtil.getCurrentUserId());
        return Result.success();
    }

    @Operation(summary = "获取会话消息")
    @GetMapping("/session/{id}/messages")
    public Result<List<ChatMessage>> getMessages(@PathVariable Long id) {
        return Result.success(chatService.getMessages(id));
    }

    // ────────── SSE Streaming Chat ──────────

    @Operation(summary = "发送问题(SSE流式)")
    @PostMapping(value = "/send", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> send(@Valid @RequestBody ChatRequest request, HttpServletRequest httpReq) {
        Long uid = SecurityUtil.getCurrentUserId();
        // Rate limit check
        String rateKey = "chat:" + uid;
        if (!rateLimitService.isAllowed(rateKey)) {
            throw new BusinessException(ResultCode.RATE_LIMITED);
        }
        StringBuilder answerBuffer = new StringBuilder();
        List<ChatMessageSource> sourcesBuffer = new ArrayList<>();
        return chatService.chatStream(request.getSessionId(), uid, request.getQuestion(),
                answerBuffer, sourcesBuffer);
    }

    // ────────── Source retrieval ──────────

    @Operation(summary = "获取消息的来源引用")
    @GetMapping("/message/{messageId}/sources")
    public Result<List<ChatMessageSource>> getSources(@PathVariable Long messageId) {
        return Result.success(sourceMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ChatMessageSource>()
                        .eq(ChatMessageSource::getMessageId, messageId)));
    }
}
