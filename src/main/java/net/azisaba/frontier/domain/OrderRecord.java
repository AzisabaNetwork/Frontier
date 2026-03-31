package net.azisaba.frontier.domain;

import java.time.Instant;
import java.util.UUID;

public record OrderRecord(
        long id,
        long seasonId,
        UUID ownerUuid,
        String ownerName,
        OrderType orderType,
        String itemKey,
        long amount,
        long unitPrice,
        long fee,
        OrderStatus status,
        UUID reservedByUuid,
        String reservedByName,
        Instant reservedAt,
        Instant createdAt,
        Instant expiresAt
) {
    public long totalPrice() {
        return this.amount * this.unitPrice;
    }

    public OrderRecord withStatus(OrderStatus newStatus) {
        return new OrderRecord(
                this.id,
                this.seasonId,
                this.ownerUuid,
                this.ownerName,
                this.orderType,
                this.itemKey,
                this.amount,
                this.unitPrice,
                this.fee,
                newStatus,
                this.reservedByUuid,
                this.reservedByName,
                this.reservedAt,
                this.createdAt,
                this.expiresAt
        );
    }

    public OrderRecord reserve(UUID playerUuid, String playerName, Instant reservedAt) {
        return new OrderRecord(
                this.id,
                this.seasonId,
                this.ownerUuid,
                this.ownerName,
                this.orderType,
                this.itemKey,
                this.amount,
                this.unitPrice,
                this.fee,
                OrderStatus.RESERVED,
                playerUuid,
                playerName,
                reservedAt,
                this.createdAt,
                this.expiresAt
        );
    }

    public OrderRecord clearReservation(OrderStatus newStatus) {
        return new OrderRecord(
                this.id,
                this.seasonId,
                this.ownerUuid,
                this.ownerName,
                this.orderType,
                this.itemKey,
                this.amount,
                this.unitPrice,
                this.fee,
                newStatus,
                null,
                null,
                null,
                this.createdAt,
                this.expiresAt
        );
    }
}
