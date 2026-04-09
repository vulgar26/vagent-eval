package com.vagent.eval.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 显式启用 {@link EvalProperties} 的配置绑定。
 * <p>
 * 仅有 {@link org.springframework.boot.context.properties.ConfigurationProperties @ConfigurationProperties}
 * 时，Spring Boot 不会自动把该类注册为 Bean（除非使用 {@code @ConfigurationPropertiesScan} 等其它方式）。
 * 本类上的 {@link EnableConfigurationProperties#value() EnableConfigurationProperties(EvalProperties.class)}
 * 会注册 {@code EvalProperties} Bean，这样 {@code InternalEvalStatusController} 等才能通过构造器注入读取 {@code eval.*}。
 * <p>
 * 若缺少这一行：其它类无法注入 {@code EvalProperties}，启动期会报「找不到 Bean」类错误。
 */
@Configuration
@EnableConfigurationProperties(EvalProperties.class)
public class EvalConfiguration {
}
