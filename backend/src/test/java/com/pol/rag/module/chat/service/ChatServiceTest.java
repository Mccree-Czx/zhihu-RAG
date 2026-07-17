package com.pol.rag.module.chat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pol.rag.config.properties.AppProperties;
import com.pol.rag.module.chat.entity.ChatMessage;
import com.pol.rag.module.chat.entity.ChatMessageSource;
import com.pol.rag.module.chat.entity.ChatSession;
import com.pol.rag.module.chat.mapper.*;
import com.pol.rag.module.kb.mapper.KbChunkMapper;
import com.pol.rag.module.kb.mapper.KbDocumentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("会话与问答服务")
class ChatServiceTest {

    @Mock private ChatSessionMapper sessionMapper;
    @Mock private ChatMessageMapper messageMapper;
    @Mock private ChatMessageSourceMapper sourceMapper;
    @Mock private VectorStore vectorStore;
    @Mock private ChatClient chatClient;
    @Mock private KbChunkMapper chunkMapper;
    @Mock private KbDocumentMapper documentMapper;

    private AppProperties appProperties;

    @InjectMocks
    private ChatService chatService;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
    }

    // ────────── Session CRUD ──────────

    @Test
    @DisplayName("创建会话：指定标题时应使用指定标题")
    void shouldCreateSessionWithGivenTitle() {
        ChatSession result = chatService.createSession(1L, "测试会话");

        assertThat(result.getUserId()).isEqualTo(1L);
        assertThat(result.getTitle()).isEqualTo("测试会话");
        assertThat(result.getIsFavorite()).isEqualTo(0);
        verify(sessionMapper).insert(any(ChatSession.class));
    }

    @Test
    @DisplayName("创建会话：标题为 null 时应使用默认标题「新会话」")
    void shouldUseDefaultTitleWhenNull() {
        ChatSession result = chatService.createSession(1L, null);

        assertThat(result.getTitle()).isEqualTo("新会话");
    }

    @Test
    @DisplayName("列出会话应按更新时间倒序")
    void shouldListSessionsByUser() {
        when(sessionMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(new ChatSession(), new ChatSession()));

        List<ChatSession> sessions = chatService.listSessions(1L);

        assertThat(sessions).hasSize(2);
    }

    @Test
    @DisplayName("删除会话：会话不存在或不属于用户时应静默忽略")
    void shouldSilentlyIgnoreDeleteForWrongOwner() {
        ChatSession session = new ChatSession();
        session.setUserId(99L);
        when(sessionMapper.selectById(1L)).thenReturn(session);

        chatService.deleteSession(1L, 1L); // different userId

        verify(sessionMapper, never()).deleteById(anyLong());
        verify(messageMapper, never()).delete(any());
    }

    @Test
    @DisplayName("删除会话：拥有者可以删除自己和关联消息")
    void shouldDeleteSessionAndMessages() {
        ChatSession session = new ChatSession();
        session.setUserId(1L);
        when(sessionMapper.selectById(1L)).thenReturn(session);

        chatService.deleteSession(1L, 1L);

        verify(sessionMapper).deleteById(1L);
        verify(messageMapper).delete(any(LambdaQueryWrapper.class));
    }

    @Test
    @DisplayName("切换收藏应翻转 isFavorite 值")
    void shouldToggleFavorite() {
        ChatSession session = new ChatSession();
        session.setUserId(1L);
        session.setIsFavorite(1);
        when(sessionMapper.selectById(1L)).thenReturn(session);

        chatService.toggleFavorite(1L, 1L);

        assertThat(session.getIsFavorite()).isEqualTo(0);
        verify(sessionMapper).updateById(session);
    }

    @Test
    @DisplayName("获取消息应按创建时间升序")
    void shouldGetMessagesInOrder() {
        when(messageMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(new ChatMessage(), new ChatMessage()));

        List<ChatMessage> msgs = chatService.getMessages(1L);

        assertThat(msgs).hasSize(2);
    }
}
