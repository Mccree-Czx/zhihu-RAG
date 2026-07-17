package com.pol.rag.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI configuration.
 *
 * <p>Spring AI 1.0 only auto-configures a {@link ChatClient.Builder} bean, not a
 * ready-to-use {@link ChatClient}. We expose a singleton {@link ChatClient} so it can be
 * injected directly (e.g. into ChatService).</p>
 */
@Configuration
public class AiConfig {

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }
}
