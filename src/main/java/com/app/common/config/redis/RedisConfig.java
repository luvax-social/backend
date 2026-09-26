package com.app.common.config.redis;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.StringUtils;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.protocol.ProtocolVersion;
import io.lettuce.core.resource.ClientResources;

/**
 * Redis client wiring. Exposes a single {@link StringRedisTemplate} for string-only operations such
 * as the JWT blacklist and the auth rate limiter; no generic value template is declared because no
 * binary or JSON-serialised values are stored from application code.
 */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RedisConfig {

    /**
     * Explicit factory that forces RESP2, preventing Lettuce 6.x from sending {@code HELLO 3}
     * before {@code AUTH} on a {@code requirepass}-protected Redis 6+ server.
     */
    @Bean
    public LettuceConnectionFactory redisConnectionFactory(
            @Value("${spring.data.redis.host:localhost}") String host,
            @Value("${spring.data.redis.port:6379}") int port,
            @Value("${spring.data.redis.password:}") String password,
            ClientResources clientResources) {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(host, port);
        if (StringUtils.hasText(password)) {
            config.setPassword(RedisPassword.of(password));
        }
        LettuceClientConfiguration clientConfig =
                LettuceClientConfiguration.builder()
                        .clientResources(clientResources)
                        .clientOptions(
                                ClientOptions.builder()
                                        .protocolVersion(ProtocolVersion.RESP2)
                                        .build())
                        .build();
        return new LettuceConnectionFactory(config, clientConfig);
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }
}
