package com.diaoyuanyun.dy.common.exception;

import com.diaoyuanyun.dy.common.result.ErrorCode;

/**
 * 业务异常基类。所有可被全局异常处理器转成 {@link com.diaoyuanyun.dy.common.result.Result} 的异常都应继承它。
 *
 * <p>{@code devMessage} 是给开发者的消息, 不直接下发客户端 UI。
 * {@code data} 用于回传附件数据 (如门禁缺失项 {@code missing_items[]})。
 */
public class BizException extends RuntimeException {

    private final int code;
    private final String devMessage;
    private final Object data;

    public BizException(ErrorCode errorCode) {
        this(errorCode, errorCode.getMessage(), null);
    }

    public BizException(ErrorCode errorCode, String devMessage) {
        this(errorCode, devMessage, null);
    }

    public BizException(ErrorCode errorCode, String devMessage, Object data) {
        super(devMessage);
        this.code = errorCode.getCode();
        this.devMessage = devMessage;
        this.data = data;
    }

    public int getCode() {
        return code;
    }

    public String getDevMessage() {
        return devMessage;
    }

    public Object getData() {
        return data;
    }
}
