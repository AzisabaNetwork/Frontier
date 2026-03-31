package net.azisaba.frontier.repository;

import net.azisaba.frontier.domain.OrderRecord;

import java.util.Collection;

public interface OrderRepository {
    OrderRecord findOrder(long orderId);

    Collection<OrderRecord> orders();

    void saveOrder(OrderRecord order);
}
