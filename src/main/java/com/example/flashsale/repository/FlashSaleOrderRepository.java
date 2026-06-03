package com.example.flashsale.repository;

import com.example.flashsale.entity.FlashSaleOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FlashSaleOrderRepository extends JpaRepository<FlashSaleOrder, Long> {
}
