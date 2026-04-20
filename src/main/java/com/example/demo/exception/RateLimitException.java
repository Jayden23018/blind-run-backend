package com.example.demo.exception;

public class RateLimitException extends RuntimeException {
    private final int retryAfterSeconds;

    public RateLimitException(int retryAfterSeconds) {
        super("请求过于频繁，请稍后再试");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
