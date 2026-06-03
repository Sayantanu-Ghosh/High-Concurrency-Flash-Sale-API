package com.example.flashsale.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "flash_sale_orders")
@Data
@NoArgsConstructor
public class FlashSaleOrder {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "item_id", nullable = false)
    private Long itemId;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Column(name = "order_time", updatable = false)
    private Instant orderTime;

    public FlashSaleOrder(Long userId, Long itemId, Integer quantity) {
        this.userId = userId;
        this.itemId = itemId;
        this.quantity = quantity;
    }

    @PrePersist
    protected void onPersist() {
        orderTime = Instant.now();
    }
}
