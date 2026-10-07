package com.videostep.locator.util;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/** 限制 InputStream 只读取指定字节数（用于 HTTP Range 流式响应） */
public class LimitedInputStream extends FilterInputStream {
    private final long limit;
    private long count = 0;

    public LimitedInputStream(InputStream in, long limit) {
        super(in);
        this.limit = limit;
    }

    @Override
    public int read() throws IOException {
        if (count >= limit) return -1;
        int b = super.read();
        if (b >= 0) count++;
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (count >= limit) return -1;
        int maxLen = (int) Math.min(len, limit - count);
        int n = super.read(b, off, maxLen);
        if (n > 0) count += n;
        return n;
    }

    @Override
    public long skip(long n) throws IOException {
        long toSkip = Math.min(n, limit - count);
        long skipped = super.skip(toSkip);
        count += skipped;
        return skipped;
    }

    @Override
    public int available() throws IOException {
        return (int) Math.min(super.available(), limit - count);
    }
}
