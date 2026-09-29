package com.diaoyuanyun.dy.app.doctpl.storage;

/**
 * 对象存储访问失败（B-4）。
 *
 * <p>🛑 存在的意义是<b>与"内容为空"区分开</b>：{@code byte[0]} 是合法内容，
 * 而"读不到"必须是一次显式失败。二者若混同，I7 下载会把存储故障渲染成一份空白原件，
 * 而空白原件在合规场景里比 500 更危险（它看起来像"本来就该是空的"）。
 */
public class BlobStoreException extends RuntimeException {

    public BlobStoreException(String message) {
        super(message);
    }

    public BlobStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}