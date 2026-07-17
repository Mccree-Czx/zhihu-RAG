package com.pol.rag.config;

import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson 序列化配置。
 *
 * <p>核心作用：将 {@link Long} / {@code long} 类型序列化为字符串返回给前端。</p>
 *
 * <p><b>原因：</b>本项目主键使用雪花算法（MyBatis-Plus {@code ASSIGN_ID}），
 * 生成的 19 位 Long ID 超过 JavaScript {@code Number.MAX_SAFE_INTEGER}（2^53-1，16 位）。
 * 若以 JSON number 返回，浏览器 {@code JSON.parse} 会丢失末位精度
 * （如 {@code ...664001} 变成 {@code ...664000}），导致前端用错误 ID 请求后端，
 * 出现「会话不存在」等异常。转为 String 可彻底避免精度丢失。</p>
 *
 * <p><b>实现方式：</b>使用 {@code @Bean} 提供一个 {@link SimpleModule}，Spring Boot 会将其
 * 自动注册到默认 {@code ObjectMapper}（而非替换），因此不会影响
 * {@code JavaTimeModule} 等其他已注册模块对 {@code LocalDateTime} 的处理。</p>
 */
@Configuration
public class JacksonConfig {

    @Bean
    public SimpleModule longToStringModule() {
        SimpleModule module = new SimpleModule();
        // Long 包装类型与 long 原始类型均转为字符串输出
        module.addSerializer(Long.class, ToStringSerializer.instance);
        module.addSerializer(Long.TYPE, ToStringSerializer.instance);
        return module;
    }
}
