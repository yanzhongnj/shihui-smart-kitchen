package com.videostep.locator.service.xunwei;

/** 寻味视觉识别异常：携带用户可读的中文提示（controller 转友好错误）。 */
public class XunweiVisionException extends RuntimeException {
    public XunweiVisionException(String message) {
        super(message);
    }
}
