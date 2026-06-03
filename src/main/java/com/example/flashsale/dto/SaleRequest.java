package com.example.flashsale.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SaleRequest {
    private Long userId;
    private Long itemId;
    private Integer quantity;
}
