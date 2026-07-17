package com.pol.rag.common.ratelimit;

import com.pol.rag.config.properties.AppProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Redis-based sliding-window rate limiter.
 */
@Service
@RequiredArgsConstructor
public class RateLimitService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final AppProperties appProperties;

    private static final String RATE_LIMIT_KEY_PREFIX = "rate_limit:";

    /**
     * Check if the request within the rate limit for the given key.
     * Returns true if allowed, false if rate limited.
     */
    public boolean isAllowed(String key) {
        String redisKey = RATE_LIMIT_KEY_PREFIX + key;
        int capacity = appProperties.getRateLimit().getChatCapacity();
        int refillMinutes = appProperties.getRateLimit().getChatRefillMinutes();

        Long current = redisTemplate.opsForValue().increment(redisKey);
        if (current != null && current == 1) {
            redisTemplate.expire(redisKey, Duration.ofMinutes(refillMinutes));
        }
        return current == null || current <= capacity;
    }

    public long getRemaining(String key) {
        String redisKey = RATE_LIMIT_KEY_PREFIX + key;
        Object val = redisTemplate.opsForValue().get(redisKey);
        if (val == null) return appProperties.getRateLimit().getChatCapacity();
        return appProperties.getRateLimit().getChatCapacity() - Long.parseLong(val.toString());
    }
}
