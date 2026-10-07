package com.videostep.locator.exception;

/** 游客配额用尽（映射 HTTP 429） */
public class QuotaExceededException extends RuntimeException {
    public QuotaExceededException(String message) {
        super(message);
    }
}
