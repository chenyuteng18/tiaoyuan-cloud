package com.diaoyuanyun.dy.crypto.key;

/**
 * 主体密钥的<b>逻辑引用</b>（租户 + 主体 + 版本），不含任何密钥材料。
 *
 * <p>单独抽出来是为了让"销毁"这件事有一个可传递、可记入删除 DAG、可写进删除审计的
 * 值对象 —— 删除权的证据链需要指向"被销毁的是哪一把密钥"，而不是指向一个内存地址。
 */
public record SubjectKeyRef(String tenantId, String subjectType, String subjectId, int version) {

    public static SubjectKeyRef of(com.diaoyuanyun.dy.crypto.envelope.SubjectRef subject, int version) {
        return new SubjectKeyRef(subject.tenantId(), subject.subjectType(), subject.subjectId(), version);
    }

    public String display() {
        return tenantId + "/" + subjectType + "/" + subjectId + " v" + version;
    }
}