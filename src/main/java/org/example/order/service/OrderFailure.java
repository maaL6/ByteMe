package org.example.order.service;

public final class OrderFailure extends RuntimeException {
    private final String code;

    public OrderFailure(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() { return code; }
}
