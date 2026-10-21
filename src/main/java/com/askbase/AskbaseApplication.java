package com.askbase;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Askbase 启动类。
 *
 * <p>启动类刻意保持极简：没有 Mapper、没有 LLM、没有业务表。
 * 目标只有一个——{@code docker compose up} 之后能打开接口文档并调通。
 * 地基没夯实就往上堆，后面每加一个功能都会多一个排查方向。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AskbaseApplication {

    public static void main(String[] args) {
        SpringApplication.run(AskbaseApplication.class, args);
    }
}
