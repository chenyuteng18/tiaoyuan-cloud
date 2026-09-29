package com.diaoyuanyun.dy.crypto.key;

/**
 * 被租户 KEK <b>包裹后</b>的 DEK —— 信封加密中"可以落库的那一份"。
 *
 * <p>它不含任何明文密钥材料。它的内容（密文 + nonce + 算法 + 版本）足以在拿到
 * 租户 KEK 之后还原出 DEK。
 *
 * <h2>⚠️ 备份纪律（DoD ④）就落在本类型上</h2>
 * 架构规格书 §8.4：DEK 备份与访问控制<b>必须与数据本身同等严格</b>，
 * 否则"密钥丢失 = 数据永久不可读"。这里有一条容易搞反的推论：
 * <ul>
 *   <li>业务密文<b>丢了</b> → 数据没了，但<b>不影响其他主体</b>；</li>
 *   <li>{@code wrappedDek} 丢了（且备份里也没有）→ 该主体数据<b>永久不可读</b>，
 *       而且这个损失是<b>不可逆</b>的（crypto-shredding 之后无法凭密文恢复）。</li>
 * </ul>
 * 所以 {@code wrappedDek} 的备份等级必须 >= 业务数据的备份等级。本类型的存在使得
 * "哪些行必须进备份、且必须与数据同周期"是可枚举的：<b>所有 WrappedDek 行</b>。
 *
 * <p>本模块<b>不</b>提供"删除 wrappedDek 但保留备份"这类接口 —— 那等于事后给自己
 * 留一个恢复通道，与 crypto-shredding 的语义直接冲突（见
 * {@link SubjectKeyStore#destroySubjectKey} 的说明）。
 */
public record WrappedDek(String tenantId, String subjectType, String subjectId,
                         int version, String kekId, String algorithmId,
                         byte[] nonce, byte[] wrappedBytes) {

    public WrappedDek {
        if (tenantId == null || subjectType == null || subjectId == null) {
            throw new IllegalArgumentException("WrappedDek 的归属三元组不可为空");
        }
        if (version < 1) {
            throw new IllegalArgumentException("DEK 版本必须 >= 1");
        }
        if (kekId == null || kekId.isBlank()) {
            throw new IllegalArgumentException("kekId 不可为空 —— "
                    + "没有它就无法判断这把 DEK 是被哪一代 KEK 包裹的，KEK 轮换即失效");
        }
        if (algorithmId == null || algorithmId.isBlank()) {
            throw new IllegalArgumentException("被包裹体也必须带算法位（回解 DEK 时用它选实现）");
        }
    }

    public String display() {
        return tenantId + "/" + subjectType + "/" + subjectId + " v" + version + "@" + kekId;
    }
}