package com.example.flashsale.repository;

import com.example.flashsale.entity.FlashSaleItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FlashSaleItemRepository extends JpaRepository<FlashSaleItem, Long> {
}
