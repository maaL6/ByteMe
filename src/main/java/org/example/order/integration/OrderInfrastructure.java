package org.example.order.integration;

import java.util.function.Supplier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import org.example.order.service.port.OrderEvents;
import org.example.order.service.port.UnitOfWork;

/** Spring details stay here so OrderService remains plain Java. */
@Component
public class OrderInfrastructure implements UnitOfWork, OrderEvents {
    private final TransactionTemplate transactions;
    private final ApplicationEventPublisher publisher;

    public OrderInfrastructure(PlatformTransactionManager manager, ApplicationEventPublisher publisher) {
        this.transactions = new TransactionTemplate(manager);
        this.publisher = publisher;
    }

    @Override public <T> T execute(Supplier<T> action) { return transactions.execute(status -> action.get()); }
    @Override public void publish(OrderCreated event) { publisher.publishEvent(event); }

    @TransactionalEventListener
    public void cartPlaceholder(OrderCreated event) {
        // TODO: Cart event hook when Cart Service is implemented.
        // clearItems still runs inside checkout, not in this after-commit listener.
    }
}
