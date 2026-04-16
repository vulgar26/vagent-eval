package com.vagent.eval;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * P0 eval 服务入口：后续在此进程中扩展 dataset / run / report / compare。
 * <p>
 * {@link SpringBootApplication} 等价于三件事打包在一起（不必手写三个注解）：
 * <ul>
 *   <li>{@code @Configuration} — 本类作为 Spring 配置，可配合 {@code @Bean} 注册组件；</li>
 *   <li>{@code @EnableAutoConfiguration} — 按 classpath 自动装配（如内嵌 Tomcat、Jackson）；</li>
 *   <li>{@code @ComponentScan} — 默认扫描「本类所在包及其子包」，把带 {@code @Component}/{@code @Service}/{@code @RestController} 等类注册成 Bean。</li>
 * </ul>
 * 因此放在 {@code com.vagent.eval} 包下时，会扫描到 {@code com.vagent.eval.config}、{@code com.vagent.eval.web} 等子包。
 * <p>
 * {@link SpringApplication#run(Class, String[])} 做的事可以记成一句话：
 * 创建 Spring 容器、完成自动配置与 Bean 装配、启动内嵌 Web 服务器（本项目的端口见 {@code application.yml} 的 {@code server.port}），然后进程常驻。
 */
@SpringBootApplication
@EnableScheduling
public class VagentEvalApplication {

    public static void main(String[] args) {
        SpringApplication.run(VagentEvalApplication.class, args);
    }
}
