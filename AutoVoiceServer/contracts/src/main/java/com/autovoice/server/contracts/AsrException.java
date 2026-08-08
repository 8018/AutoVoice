package com.autovoice.server.contracts;

/** ASR 失败异常（语音识别服务不可用、超时、解码失败等）。 */
public class AsrException extends RuntimeException {

    public AsrException(String message) {
        super(message);
    }

    public AsrException(String message, Throwable cause) {
        super(message, cause);
    }
}
