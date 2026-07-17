package com.pol.rag.common.ratelimit;

import com.pol.rag.config.properties.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("限流服务")
class RateLimitServiceTest {

    @Mock private RedisTemplate<String, Object> redisTemplate;
    @Mock private ValueOperations<String, Object> valueOperations;

    @Spy
    private AppProperties appProperties = new AppProperties();

    @InjectMocks
    private RateLimitService rateLimitService;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    @DisplayName("首次请求应在容量内通过")
    void shouldAllowFirstRequest() {
        when(valueOperations.increment("rate_limit:chat:1")).thenReturn(1L);

        assertThat(rateLimitService.isAllowed("chat:1")).isTrue();
        verify(redisTemplate).expire(eq("rate_limit:chat:1"), any(Duration.class));
    }

    @Test
    @DisplayName("超过容量应被限流拒绝")
    void shouldRejectWhenOverCapacity() {
        // capacity default = 30
        when(valueOperations.increment("rate_limit:chat:1")).thenReturn(31L);

        assertThat(rateLimitService.isAllowed("chat:1")).isFalse();
    }

    @Test
    @DisplayName("达到容量边界值应仍允许")
    void shouldAllowAtCapacityBoundary() {
        when(valueOperations.increment("rate_limit:chat:1")).thenReturn(30L);

        assertThat(rateLimitService.isAllowed("chat:1")).isTrue();
    }

    @Test
    @DisplayName("获取剩余配额：首次应返回满容量")
    void shouldReturnFullCapacityForNewKey() {
        when(valueOperations.get("rate_limit:chat:1")).thenReturn(null);

        assertThat(rateLimitService.getRemaining("chat:1")).isEqualTo(30L);
    }

    @Test
    @DisplayName("获取剩余配额：已消耗部分应正确计算")
    void shouldReturnCorrectRemaining() {
        when(valueOperations.get("rate_limit:chat:1")).thenReturn("5");

        assertThat(rateLimitService.getRemaining("chat:1")).isEqualTo(25L);
    }
}
