package com.diaoyuanyun.dy.web.observability;

/**
 * 日志 logger 名约定 (A7 DoD #3): <b>审计日志与应用日志物理分离</b>的命名层。
 *
 * <h2>为什么"用哪个 logger"必须是一个约定的常量, 而不是随手一个字符串</h2>
 * 日志的物理分离（不同 appender / 不同文件 / 不同保留策略）在 Logback 里是<b>按 logger 名绑定</b>的。
 * 于是"审计条目写在哪"完全取决于调用方随手写的字符串。只要有一处写成
 * {@code LoggerFactory.getLogger("audit")} 而配置里绑的是 {@code DY.AUDIT}, 那条审计记录就会
 * <b>安静地</b>落进应用日志文件 —— 没有报错、没有告警, 只是审计文件里少了一条。
 * 这类"静默丢审计"在此前出现过太多次, 故把名字提成常量, 让"写错名字"变成编译不过 / 一眼可见。
 *
 * <h2>两个名字必须互不嵌套</h2>
 * Logback 的 logger 层级是<b>按名字前缀</b>继承配置的。若 {@code AUDIT = "DY"} 而
 * {@code APP = "DY.APP"}, 则 APP 是 AUDIT 的子 logger, 会继承 AUDIT 的 appender ——
 * 于是应用日志也会被写进审计文件, 分离失效。故本类在静态块里<b>自检</b>两者互不为前缀,
 * 名字写错就在类加载时立刻失败, 而不是等到审计对不上账才发现。
 *
 * <h2>logger 名的形状选择</h2>
 * 用全大写的 {@code DY.APP} / {@code DY.AUDIT} 而不是包名风格的
 * {@code com.diaoyuanyun.dy.audit}：后者的前缀恰好与 {@code com.diaoyuanyun.dy...}
 * 下的业务 logger 重叠, 会让"审计 logger 绑定的 appender"顺着前缀继承关系泄漏到一大片
 * 业务 logger 上。取一个与包名无关的名字空间, 隔离是干净的。
 */
public final class DyLoggers {

    /** 应用日志 logger (普通业务/技术日志)。 */
    public static final String APP = "DY.APP";

    /**
     * 审计日志 logger。审计条目（谁在什么时候对哪条数据做了什么）必须走这一条,
     * 使它与应用日志落到不同文件、可设不同保留策略、可分别授权访问。
     */
    public static final String AUDIT = "DY.AUDIT";

    private DyLoggers() {
    }

    static {
        // 自检: 两者互不为前缀, 否则 Logback 的前缀继承会让 appender 串味 (见类注释)
        if (APP.startsWith(AUDIT + ".") || AUDIT.startsWith(APP + ".") || APP.equals(AUDIT)) {
            throw new IllegalStateException(
                    "DyLoggers 命名冲突: APP=" + APP + " 与 AUDIT=" + AUDIT
                            + " 互为前缀或相同 —— Logback 会按名字前缀继承 appender, 导致审计与应用日志物理分离失效");
        }
    }
}