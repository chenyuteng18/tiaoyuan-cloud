package com.diaoyuanyun.dy.crypto.key;

/** 该主体从未创建过 DEK（与"已销毁"是两件事，故用两个异常类型）。 */
public class SubjectKeyNotFoundException extends RuntimeException {

    public SubjectKeyNotFoundException(String display) {
        super("未找到主体密钥: " + display + " —— 该主体从未加密过数据，"
                + "与本模块的 SubjectKeyDestroyedException（已依法销毁）是两种不同状态，"
                + "调用方需要分别处置（前者通常是逻辑错误，后者是删除权的预期结果）");
    }
}