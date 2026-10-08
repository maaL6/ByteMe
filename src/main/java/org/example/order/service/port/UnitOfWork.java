package org.example.order.service.port;

import java.util.function.Supplier;

public interface UnitOfWork {
    <T> T execute(Supplier<T> action);
}
