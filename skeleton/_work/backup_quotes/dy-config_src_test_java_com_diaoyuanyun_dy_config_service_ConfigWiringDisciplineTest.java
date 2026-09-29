package com.diaoyuanyun.dy.config.service;

import com.diaoyuanyun.dy.config.repository.JdbcConfigService;
import com.diaoyuanyun.dy.config.validation.DefaultConfigValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ConfigWiringDisciplineTest: <b>内存实现不得成为可装配的运行时入口</b>（B-3 的装配栅栏）。
 *
 * <h2>它守的是什么（一个真实的、此前无人把守的装配风险）</h2>
 * {@link ConfigServiceImpl}（内存、无租户维度）与
 * {@link JdbcConfigService}（DB 真相源、逐操作 RLS、触发器留痕）
 * <b>曾经同时</b>标着 {@code @Service}。而 {@code ConfigService} 接口
 * 的生产注入方<b>为零</b> —— 这构成一个危险的组合：
 * <ul>
 *   <li>"接口零注入方"<b>不等于</b>"类不会被装配"。一旦有人按类型注入
 *       {@code ConfigService}（最自然的用法就是注入接口），Spring 会解析到<b>内存实现</b>；</li>
 *   <li>{@code JdbcConfigService} <b>不 implements</b> {@code ConfigService}
 *       （它每个方法都要显式 {@code tenantId}，与接口签名不兼容）
 *       ⇒ 按接口注入<b>只能</b>拿到内存实现，拿不到真相源。
 *       这不是"两个 Bean 择一"，是"接口指向了错误的那个"。</li>
 * </ul>
 * 一旦误装配，配置变更落进进程内存：<b>重启即丢、无留痕、无 RLS</b> ——
 * 而症状是"改了配置好像生效过，重启后变了回去"，与"配置服务有问题"隔了一层。
 *
 * <p>故 B-3 去掉 {@code ConfigServiceImpl} 的 {@code @Service}，并由本类把它
 * 从<b>注释纪律</b>升级为<b>构建期事实</b>。
 *
 * <h2>为什么用反射而不是"读源码文本 grep"</h2>
 * 反射断言的是<b>编译产物</b>（class 文件上的注解），与 Spring 在运行期看到的是同一份东西。
 * 读源码文本只能证明"我写的时候没加"，改不动"class 里到底有没有" ——
 * 增量编译、注解处理器、或某个打包步骤都可能让两者分叉。
 * 同类做法在本仓已有先例：{@code ConfigTruthSourceIT#no_external_config_center_is_on_the_read_path}
 * 也是反射检查字段类型，而不是断言"我写的常量里没有 Apollo"。
 */
@DisplayName("配置装配纪律：内存实现不得标 @Service；DB 真相源是唯一运行时入口")
class ConfigWiringDisciplineTest {

    /** Spring 的装配注解全集：任一出现都意味着这个类会被容器扫描到。 */
    private static final String[] SPRING_STEREOTYPES = {
            "org.springframework.stereotype.Component",
            "org.springframework.stereotype.Service",
            "org.springframework.stereotype.Repository",
            "org.springframework.stereotype.Controller",
            "org.springframework.context.annotation.Configuration",
    };

    @Test
    @DisplayName("🛑 内存实现 ConfigServiceImpl 不得带任何一个 Spring 装配注解（否则可被误注入）")
    void in_memory_impl_carries_no_spring_stereotype() {
        Class<?> c = ConfigServiceImpl.class;
        for (String fqn : SPRING_STEREOTYPES) {
            assertFalse(hasAnnotation(c, fqn),
                    "🛑 " + c.getName() + " 带有 Spring 装配注解 @" + simple(fqn) + " —— "
                            + "它会成为一个可被按类型注入的 Bean。按 ConfigService 接口注入时，"
                            + "Spring 只能解析到这个内存实现（JdbcConfigService 不 implements 该接口）"
                            + "⇒ 配置变更落进进程内存：重启即丢、无留痕、无 RLS。"
                            + "若确需让容器持有它，必须同时提供明确的装配约束"
                            + "（例如 @Primary 指向真相源 + 显式命名），并更新本断言。");
        }
    }

    @Test
    @DisplayName("内存实现仍是可直接 new 的语义载体（去掉注解不得破坏它的可测性）")
    void in_memory_impl_is_still_constructible_for_its_semantic_tests() {
        // ① 构造器必须仍可访问（ConfigServiceFailClosedTest 用它把两条语义立成事实）
        assertTrue(Modifier.isPublic(ConfigServiceImpl.class.getModifiers())
                        || !Modifier.isPrivate(ConfigServiceImpl.class.getModifiers()),
                "ConfigServiceImpl 必须仍可直接实例化（它的价值 = fail-closed 与保存时校验两条语义，"
                        + "这两条不需要容器就能证明）");
        assertTrue(java.util.Arrays.stream(ConfigServiceImpl.class.getDeclaredConstructors())
                        .anyMatch(ct -> ct.getParameterCount() == 1
                                && ct.getParameterTypes()[0].isAssignableFrom(DefaultConfigValidator.class)),
                "ConfigServiceImpl(ConfigValidator) 构造器必须仍存在");

        // ② 语义仍然生效 —— 去掉注解是装配层面的改动，不得改变任何行为
        ConfigServiceImpl svc = new ConfigServiceImpl(new DefaultConfigValidator());
        svc.set("cfg:threshold.max_retention_days", "90");
        assertEquals("90", svc.getRequired("cfg:threshold.max_retention_days"),
                "去注解不得改变读取行为");
    }

    @Test
    @DisplayName("DB 真相源 JdbcConfigService 仍是唯一标 @Service 的配置服务实现")
    void db_truth_source_is_the_only_service_annotated_config_implementation() {
        assertTrue(hasAnnotation(JdbcConfigService.class,
                        "org.springframework.stereotype.Service"),
                "🛑 JdbcConfigService 必须带 @Service —— 它是 ADR-08 的运行时实现"
                        + "（DB 唯一真相源 + 逐操作 RLS + 触发器留痕）。"
                        + "若它也去掉了注解，配置服务就没有任何容器可见的实现，"
                        + "将来接线时会有人「顺手」把内存实现加回注解。");

        // 且它【不得】implements ConfigService：这不是疏漏，而是刻意的签名不兼容
        // （它的每个方法都要显式 tenantId，而接口是无租户维度的）
        assertFalse(java.util.Arrays.stream(JdbcConfigService.class.getInterfaces())
                        .anyMatch(i -> i.getSimpleName().equals("ConfigService")),
                "🛑 JdbcConfigService 刻意【不】implements ConfigService —— "
                        + "它每个方法都要求显式 tenantId（RLS 上下文与缓存键必须同源），"
                        + "而 ConfigService 的方法都不带租户维度。若将来有人让它实现该接口，"
                        + "就必须把租户维度塞进方法签名或线程上下文 —— "
                        + "那是安全评审级改动，本断言在此拦截它的静默发生。");
    }

    @Test
    @DisplayName("接口 ConfigService 必须仍无租户维度（签名变化 = 装配语义变化，必须显式表态）")
    void the_interface_stays_tenant_dimension_free() {
        Class<?> itf = ConfigService.class;
        assertTrue(itf.isInterface(), "ConfigService 必须是接口");
        String[] expected = {"getRequired", "getOptional", "set“};
        for (String name : expected) {
            assertTrue(java.util.Arrays.stream(itf.getDeclaredMethods())
                            .anyMatch(m -> m.getName().equals(name)),
                    ”ConfigService 必须仍声明 " + name + "（它服务于内存实现的语义测试）");
        }
        // 所有方法的参数都不得出现 tenantId 语义：这是"接口不带租户维度"的机械口径
        java.util.Arrays.stream(itf.getDeclaredMethods()).forEach(m -> {
            for (Class<?> p : m.getParameterTypes()) {
                assertFalse(p.getSimpleName().toLowerCase().contains("tenant"),
                        "🛑 ConfigService#" + m.getName() + " 出现租户类型参数（" + p.getName() + "）—— "
                                + "接口一旦引入租户维度，内存实现（无租户）与 DB 实现（强租户）"
                                + "就会变成'同一个接口的两个语义不同的实现'，"
                                + "按接口注入时将无法判断拿到的是哪一个。");
            }
        });
    }

    private static boolean hasAnnotation(Class<?> c, String fqn) {
        for (Annotation a : c.getAnnotations()) {
            if (a.annotationType().getName().equals(fqn)) {
                return true;
            }
        }
        return false;
    }

    private static String simple(String fqn) {
        return fqn.substring(fqn.lastIndexOf('.') + 1);
    }
}