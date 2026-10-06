package com.paper.mill.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 领料请求轨迹。state：processing/success/failed。
 */
public record IssueAttemptView(
        String requestId,
        Long orderId,
        String orderNo,
        Long rollId,
        String rollNo,
        Integer lengthM,
        BigDecimal weightKg,
        String operatorName,
        String state,
        String failureReason,
        Long issueId,
        LocalDateTime createdAt,
        LocalDateTime finishedAt
) {
}
