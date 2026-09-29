package com.diaoyuanyun.dy.crypto.key;

/**
 * 该主体 / 租户的密钥已被<b>销毁</b> —— 密文仍在，但已不可恢复。
 *
 * <h2>为什么这个异常不是"错误"，而是"预期结果"</h2>
 * PIPL 删除权被执行后，再尝试读取该主体数据，<b>必然</b>走到这里。
 * 因此它必须是一个<b>可识别的类型</b>，而不是被压成一个泛泛的
 * {@code RuntimeException}：
 * <ul>
 *   <li>业务侧需要把"数据已被依法删除"呈现成 404 语义（数据不存在），
 *       而不是 500（系统故障）—— 两者对用户的含义完全不同；</li>
 *   <li>运维侧需要能排除它：一条"网关 500 率升高"的告警里若混进了大量
 *       已删除主体的读取，会把人引去查错误的方向；</li>
 *   <li>审计侧需要分辨"解密失败"与"密钥已依法销毁"——后者本身是一条
 *       应当留痕的合规事实。</li>
 * </ul>
 *
 * <p>措辞纪律：消息里说"<b>已销毁</b>且不可恢复"，不说"读取失败"。
 * 前者是设计结果，后者暗示可以重试。
 */
public class SubjectKeyDestroyedException extends RuntimeException {

    private final transient SubjectKeyRef ref;

    public SubjectKeyDestroyedException(SubjectKeyRef ref) {
        super("主体密钥已销毁（" + ref.display() + "）：密文仍在但已不可恢复。"
                + "这是 crypto-shredding 的预期结果（PIPL 删除权），不是系统故障 —— "
                + "不得重试、不得据此回滚删除操作。");
        this.ref = ref;
    }

    public SubjectKeyRef ref() {
        return ref;
    }
}