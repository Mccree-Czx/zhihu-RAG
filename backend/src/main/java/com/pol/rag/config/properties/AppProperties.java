package com.pol.rag.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Application-level custom properties bound from the {@code app.*} namespace.
 */
@Data
@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private final Jwt jwt = new Jwt();
    private final Storage storage = new Storage();
    private final Rag rag = new Rag();
    private final RateLimit rateLimit = new RateLimit();

    @Data
    public static class Jwt {
        private String secret;
        private long accessTokenExpireMinutes = 120;
        private long refreshTokenExpireDays = 7;
        private String issuer = "rag-kb";
    }

    @Data
    public static class Storage {
        private String rootPath = "./data/uploads";
    }

    @Data
    public static class Rag {
        private int topK = 5;
        private double similarityThreshold = 0.35;
        private int maxHistoryRounds = 6;
        private int chunkSize = 500;
        private int chunkOverlap = 80;
    }

    @Data
    public static class RateLimit {
        private int chatCapacity = 30;
        private int chatRefillMinutes = 1;
    }
}
