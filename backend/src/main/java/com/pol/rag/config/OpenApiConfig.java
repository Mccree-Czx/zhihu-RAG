package com.pol.rag.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.Components;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI / Knife4j documentation configuration with JWT bearer auth.
 */
@Configuration
public class OpenApiConfig {

    private static final String SECURITY_SCHEME = "Bearer";

    @Bean
    public OpenAPI ragOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("政治经济学 RAG 知识库问答系统 API")
                        .description("企业级检索增强生成知识库问答系统接口文档")
                        .version("1.0.0")
                        .contact(new Contact().name("RAG KB")))
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME))
                .components(new Components().addSecuritySchemes(SECURITY_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
