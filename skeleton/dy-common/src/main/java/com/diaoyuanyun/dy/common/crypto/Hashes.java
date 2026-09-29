package com.diaoyuanyun.dy.common.crypto;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 🛑 <b>本仓唯一的 SHA-256 摘要口径</b>（小写 hex）。
 *
 * <h2>为什么它必须在 {@code dy-common}，而不是某个 {@code *Service}</h2>
 *
 * <p>本方法原先只存在于 {@code app.doctpl.service.DocFileService#sha256(byte[])}。
 * 当 {@code agreement} 域的<b>领域模型</b>（{@code AgreementRecord} /
 * {@code AgreementSnapshot}）需要"读侧重算核验"时，它只能
 * {@code DocFileService.sha256(...)} ⇒ <b>domain 反向依赖了 service</b>。
 *
 * <p>这被架构门禁 {@code ArchitectureBoundaryTest} 的
 * {@code R4-domain-model-must-not-depend-on-service} 抓红（3 处）。这不是"门禁太严"，
 * 而是一条真实的设计后果：领域模型一旦依赖服务层，就<b>无法脱离 Spring 上下文独立测试</b>。
 *
 * <p>⇒ 修法不是"给 R4 开个例外"（那是把纪律降级成装饰），而是<b>把这件能力下沉到依赖方向正确的位置</b>：
 * 摘要算法是<b>无状态纯函数</b>，它本来就属于最底层工具，不属于任何"服务"。
 * 下沉到 {@code dy-common} 之后：
 * <ul>
 *   <li>{@code domain} 依赖 {@code common} —— 合法（{@code common} 是最底层可被所有层依赖）；</li>
 *   <li>{@code DocFileService} 委托本类 —— 口径仍然<b>只有一个</b>（不是抄了一份）；</li>
 *   <li>R4 从"被绕过"变成"被更强地满足"。</li>
 * </ul>
 *
 * <h2>🛑 为什么不复制一份算法到 domain</h2>
 *
 * <p>"复制一份 in domain 类"能让门禁变绿，但会把本仓最忌讳的东西造出来：
 * <b>两个各自实现、都自称 SHA-256 的口径</b>。它们今天等价，明天某一边加了
 * 前缀、换了编码（base64 / 带 {@code sha256:} 前缀）或改了 hex 大小写，
 * 就会在最要命的地方分叉 —— {@code rendered_hash} 的核验会开始"有时对有时不对"，
 * 而<b>没有任何一条判据会红</b>。
 *
 * <p>故：全仓唯一实现 + 各调用方委托。{@code DocFileService} 的
 * {@code file_hash} 核验与 {@code agreement} 的 {@code rendered_hash} 核验
 * <b>共用同一行代码</b>，这是"口径一致"最直接的保证。
 *
 * <h2>🛑 为什么失败时抛异常而不返回 null</h2>
 *
 * <p>SHA-256 是 JDK 强制提供的算法（{@code MessageDigest.getInstance("SHA-256")}
 * 在任何合规 JRE 上都成立）。取不到说明运行环境被削过 —— 此时<b>绝不能</b>回落到
 * 其它算法（{@code String.hashCode} / MD5）来"让流程继续"：那会造出一个看起来正常、
 * 实则不具备内容寻址性质的摘要，而下游所有核验都会静默失去意义。
 * 这与 {@code app.derived.domain.ThresholdVersionFingerprint} 的判断逐字同款。
 *
 * <h2>为什么这个类不实现"验证"只实现"计算"</h2>
 *
 * <p>调用方各有各的比对策略（{@code DocFileService} 用 {@code equalsIgnoreCase}、
 * {@code AgreementRecord} 用 {@code equalsIgnoreCase} 且允许历史行大写）。
 * "归一化后比较"是调用方的语义，不是摘要函数的语义。
 */
public final class Hashes {

    /** 本仓唯一的摘要算法名。 */
    public static final String ALGORITHM = "SHA-256";

    private Hashes() {
    }

    /**
     * 计算 {@code bytes} 的 SHA-256 摘要，返回<b>小写</b> 64 位 hex。
     *
     * @param bytes 待摘要字节；不可为 {@code null}
     * @return 小写 hex 摘要（长度恒为 64）
     * @throws BizException 运行环境缺少 {@link #ALGORITHM} 时（绝不回落其它算法）
     */
    public static String sha256(byte[] bytes) {
        if (bytes == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "sha256 的入参不可为 null —— 摘要的对象必须存在");
        }
        try {
            MessageDigest md = MessageDigest.getInstance(ALGORITHM);
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // 🛑 绝不回落到其它算法 —— 见类注释「为什么失败时抛异常而不返回 null」。
            throw new BizException(ErrorCode.INTERNAL_ERROR,
                    "运行环境缺少 " + ALGORITHM + " —— 无法计算摘要。"
                            + "摘要不具备内容寻址性质时，下游全部核验都会静默失去意义，"
                            + "故不回落到任何替代算法");
        }
    }

    /**
     * 计算 UTF-8 字符串的 SHA-256 摘要（小写 hex）。
     *
     * <p>便捷重载：调用方几乎总是对"文本正文"摘要（渲染稿 / 文件内容），
     * 而"用 UTF-8 编码"这件事在本仓是<b>唯一约定</b>。把它固定在此处，
     * 避免各调用点各写一次 {@code getBytes(StandardCharsets.UTF_8)} 而漏掉其一。
     */
    public static String sha256Utf8(String text) {
        if (text == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "sha256Utf8 的入参不可为 null —— 摘要的对象必须存在");
        }
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }
}