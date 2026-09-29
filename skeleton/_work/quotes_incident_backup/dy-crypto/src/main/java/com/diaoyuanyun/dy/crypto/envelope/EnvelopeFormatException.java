package com.diaoyuanyun.dy.crypto.envelope;

/** 信封文本无法解析。见 {@link CipherEnvelope#parse(String)}。 */
public class EnvelopeFormatException extends RuntimeException {

    public EnvelopeFormatException(String message) {
        super(message);
    }

    public EnvelopeFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}