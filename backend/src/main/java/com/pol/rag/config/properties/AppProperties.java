package com.pol.rag.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 应用级自定义配置，绑定 {@code app.*} 命名空间。
 *
 * <p>含四个子配置组：
 * <ul>
 *   <li>{@link Jwt} — JWT 密钥、过期时间</li>
 *   <li>{@link Storage} — 文档上传存储根目录</li>
 *   <li>{@link Rag} — RAG 检索参数（topK、相似度阈值、分块大小、历史轮数）</li>
 *   <li>{@link RateLimit} — 问答限流参数（令牌桶容量、重置周期）</li>
 * </ul>
 */
@Data
@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private final Jwt jwt = new Jwt();
    private final Storage storage = new Storage();
    private final Rag rag = new Rag();
    private final RateLimit rateLimit = new RateLimit();

    /** JWT 配置：密钥（Base64 编码）、access/refresh token 过期时间、签发者 */
    @Data
    public static class Jwt {
        private String secret;
        private long accessTokenExpireMinutes = 120;
        private long refreshTokenExpireDays = 7;
        private String issuer = "rag-kb";
    }

    /** 文件存储配置：上传文档的本地保存根目录 */
    @Data
    public static class Storage {
        private String rootPath = "./data/uploads";
    }

    /** RAG 检索与分块参数：topK、相似度阈值、历史轮数、chunk 大小/重叠 */
    @Data
    public static class Rag {
        private int topK = 5;
        private double similarityThreshold = 0.35;
        private int maxHistoryRounds = 6;
        private int chunkSize = 500;
        private int chunkOverlap = 80;
    }

    /** 限流配置：聊天接口令牌桶容量与重置周期（分钟） */
    @Data
    public static class RateLimit {
        private int chatCapacity = 30;
        private int chatRefillMinutes = 1;
    }
}
