package com.paper.mill.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 领料成功记录的追溯视图，供订单详情与母卷导轨共同使用。
 */
public record IssueView(
        Long id,
        String requestId,
        Long orderId,
        String orderNo,
        Long rollId,
        String rollNo,
        Integer lengthM,
        BigDecimal weightKg,
        Integer beforeLengthM,
        BigDecimal beforeWeightKg,
        Integer afterLengthM,
        BigDecimal afterWeightKg,
        LocalDateTime operatedAt,
        String operatorName
) {
}
