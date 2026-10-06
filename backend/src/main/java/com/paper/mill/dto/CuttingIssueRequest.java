package com.paper.mill.dto;

import java.math.BigDecimal;

/**
 * 切裁领料提交请求。
 */
public record CuttingIssueRequest(
        String requestId,
        Long orderId,
        Long rollId,
        Integer lengthM,
        BigDecimal weightKg,
        String operatorName
) {
}
