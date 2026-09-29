package com.diaoyuanyun.dy.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 调元云骨架启动类 (ADR-03 Modulith)。
 *
 * <p>统一扫描 {@code com.diaoyuanyun} 下所有模块 bean; 模块间依赖方向由 Maven 编译作用域强制 (ADR-03),
 * 并由 {@code ArchitectureBoundaryTest} 在 CI 二次守护。
 */
@SpringBootApplication(scanBasePackages = "com.diaoyuanyun")
public class DyAppApplication {

    public static void main(String[] args) {
        SpringApplication.run(DyAppApplication.class, args);
    }
}
