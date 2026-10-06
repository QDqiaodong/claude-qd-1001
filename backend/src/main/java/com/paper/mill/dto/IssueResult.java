package com.paper.mill.dto;

import java.math.BigDecimal;

/**
 * 领料提交的完整业务结果。业务失败也返回 200 + success=false，由前端按同一结果处理。
 */
public record IssueResult(
        String requestId,
        boolean success,
        boolean duplicate,
        String message,
        String failureReason,
        Long issueId,
        Long orderId,
        String orderNo,
        Long rollId,
        String rollNo,
        Integer requestedLengthM,
        BigDecimal requestedWeightKg,
        Integer remainLengthM,
        BigDecimal remainWeightKg,
        String rollStatus,
        IssueView issue
) {

    public static IssueResult failed(String requestId,
                                     Long orderId,
                                     String orderNo,
                                     Long rollId,
                                     String rollNo,
                                     Integer requestedLengthM,
                                     BigDecimal requestedWeightKg,
                                     Integer remainLengthM,
                                     BigDecimal remainWeightKg,
                                     String rollStatus,
                                     String reason,
                                     boolean duplicate) {
        return new IssueResult(
                requestId, false, duplicate, reason, reason, null,
                orderId, orderNo, rollId, rollNo,
                requestedLengthM, requestedWeightKg,
                remainLengthM, remainWeightKg, rollStatus, null);
    }
}
