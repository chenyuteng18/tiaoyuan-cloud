package com.diaoyuanyun.dy.crypto.key;

/**
 * 在"该加密却没有可用的密钥备份"时抛出 —— DoD ④ 的 fail-closed 出口。
 *
 * <p>它出现的位置很关键：<b>加密路径</b>，而不是"删除路径"或某个定时巡检。
 * 理由：备份纪律要防的失败模式是"密钥丢了，数据永久不可读"。这个损失在
 * <b>写入时</b>就已经注定了 —— 如果那一次的 DEK 没有被备份，之后无论做什么都无法补救。
 * 因此门必须设在写入这一侧，而不是靠事后对账。
 *
 * <p>报错信息里明确写出"处置方式"，因为这条异常会让一个本来能跑通的功能
 * 突然拒绝服务，运维的第一反应往往是"把它调宽"：
 * 调宽（例如把 {@code backupReady} 改成恒真）正好是 undo 掉这条纪律的方式，
 * 所以消息里要说清"该修的是备份，不是这个开关"。
 */
public class KeyBackupUnavailableException extends RuntimeException {

    private final transient SubjectKeyRef ref;

    /** 精确指出卡在哪一道检查上，避免"备份没就绪"这种笼统说法让人误以为是磁盘问题。 */
    public KeyBackupUnavailableException(SubjectKeyRef ref, String detail) {
        super("拒绝为该主体创建 DEK：密钥备份未就绪（" + ref.display() + "）。" + detail
                + " —— 这是 DoD ④『DEK 备份纪律』的 fail-closed 实现："
                + "未被备份的 DEK 一旦丢失，该主体数据将【永久不可读】（架构规格书 §8.4 / 竞析 H.2-9）。"
                + "处置方式：修复密钥备份（备份等级须与业务数据同等严格），"
                + "而不是放松本检查。");
        this.ref = ref;
    }

    public SubjectKeyRef ref() {
        return ref;
    }
}