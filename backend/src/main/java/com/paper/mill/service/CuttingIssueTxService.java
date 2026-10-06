package com.paper.mill.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.paper.mill.common.BizException;
import com.paper.mill.common.Dict;
import com.paper.mill.dto.CuttingIssueRequest;
import com.paper.mill.dto.IssueResult;
import com.paper.mill.dto.IssueView;
import com.paper.mill.entity.CuttingIssue;
import com.paper.mill.entity.CuttingIssueAttempt;
import com.paper.mill.entity.CuttingOrder;
import com.paper.mill.entity.PaperRoll;
import com.paper.mill.repository.CuttingIssueAttemptRepository;
import com.paper.mill.repository.CuttingIssueRepository;
import com.paper.mill.repository.CuttingOrderRepository;
import com.paper.mill.repository.PaperRollRepository;

/**
 * 切裁领料内部事务服务。
 */
@Service
public class CuttingIssueTxService {

    private final CuttingIssueAttemptRepository attemptRepository;
    private final CuttingIssueRepository issueRepository;
    private final CuttingOrderRepository orderRepository;
    private final PaperRollRepository rollRepository;

    public CuttingIssueTxService(CuttingIssueAttemptRepository attemptRepository,
                                 CuttingIssueRepository issueRepository,
                                 CuttingOrderRepository orderRepository,
                                 PaperRollRepository rollRepository) {
        this.attemptRepository = attemptRepository;
        this.issueRepository = issueRepository;
        this.orderRepository = orderRepository;
        this.rollRepository = rollRepository;
    }

    @Transactional
    public CuttingIssueAttempt createAttempt(CuttingIssueRequest request, String payloadHash) {
        LocalDateTime now = LocalDateTime.now();
        CuttingIssueAttempt attempt = new CuttingIssueAttempt();
        attempt.setRequestId(request.requestId().trim());
        attempt.setPayloadHash(payloadHash);
        attempt.setState(CuttingIssueAttempt.STATE_PROCESSING);
        attempt.setCreatedAt(now);
        if (request.orderId() != null) {
            attempt.setOrderId(request.orderId());
        }
        if (request.rollId() != null) {
            attempt.setRollId(request.rollId());
        }
        attempt.setIssueLengthM(request.lengthM());
        attempt.setIssueWeightKg(request.weightKg());
        attempt.setOperatorName(request.operatorName().trim());
        return attemptRepository.saveAndFlush(attempt);
    }

    @Transactional
    public CuttingIssueAttempt lockAttempt(String requestId) {
        return attemptRepository.findByRequestIdForUpdate(requestId)
                .orElseThrow(() -> new BizException("原领料结果不存在：" + requestId));
    }

    @Transactional
    public IssueResult process(Long attemptId) {
        CuttingIssueAttempt attempt = attemptRepository.findById(attemptId)
                .orElseThrow(() -> new BizException("领料请求不存在：" + attemptId));
        try {
            return doProcess(attempt);
        } catch (BizException e) {
            return markFailed(attempt, null, null, e.getMessage());
        }
    }

    private IssueResult doProcess(CuttingIssueAttempt attempt) {
        CuttingIssueRequest request = new CuttingIssueRequest(
                attempt.getRequestId(),
                attempt.getOrderId(),
                attempt.getRollId(),
                attempt.getIssueLengthM(),
                attempt.getIssueWeightKg(),
                attempt.getOperatorName());

        if (request.orderId() == null) {
            return markFailed(attempt, null, null, "必须指定切纸订单");
        }
        if (request.rollId() == null) {
            CuttingOrder order = orderRepository.findById(request.orderId()).orElse(null);
            return markFailed(attempt, order, null, "必须指定要领料的母卷");
        }

        CuttingOrder order = orderRepository.findById(request.orderId()).orElse(null);
        if (order == null) {
            PaperRoll roll = rollRepository.findById(request.rollId()).orElse(null);
            return markFailed(attempt, null, roll, "切纸订单不存在：" + request.orderId());
        }
        // 先锁母卷行。两名操作员并发领同一卷时，后一个事务在这里等前一个提交后，再按新余量校验。
        PaperRoll roll = rollRepository.findByIdForUpdate(request.rollId()).orElse(null);
        if (roll == null) {
            return markFailed(attempt, order, null, "母卷不存在：" + request.rollId());
        }

        String problem = validateBusiness(order, roll, request.lengthM(), request.weightKg());
        if (problem != null) {
            return markFailed(attempt, order, roll, problem);
        }

        int length = request.lengthM();
        BigDecimal weight = request.weightKg().setScale(2, java.math.RoundingMode.HALF_UP);
        int beforeLength = roll.getLengthM();
        BigDecimal beforeWeight = roll.getWeightKg();
        int afterLength = beforeLength - length;
        BigDecimal afterWeight = beforeWeight.subtract(weight);
        LocalDateTime now = LocalDateTime.now();

        CuttingIssue issue = new CuttingIssue();
        issue.setRequestId(request.requestId());
        issue.setOrder(order);
        issue.setRoll(roll);
        issue.setIssueLengthM(length);
        issue.setIssueWeightKg(weight);
        issue.setBeforeLengthM(beforeLength);
        issue.setBeforeWeightKg(beforeWeight);
        issue.setAfterLengthM(afterLength);
        issue.setAfterWeightKg(afterWeight);
        issue.setOperatedAt(now);
        issue.setOperatorName(request.operatorName());

        roll.setLengthM(afterLength);
        roll.setWeightKg(afterWeight);
        if (afterLength < Dict.MIN_REMAIN_LENGTH_M) {
            roll.setStatus("已切完");
        }

        issue = issueRepository.save(issue);
        attempt.setState(CuttingIssueAttempt.STATE_SUCCESS);
        attempt.setFailureReason(null);
        attempt.setIssue(issue);
        attempt.setIssueLengthM(length);
        attempt.setIssueWeightKg(weight);
        attempt.setFinishedAt(now);
        attemptRepository.save(attempt);
        rollRepository.save(roll);

        IssueView issueView = new IssueView(
                issue.getId(), issue.getRequestId(), order.getId(), order.getOrderNo(),
                roll.getId(), roll.getRollNo(), length, weight,
                beforeLength, beforeWeight, afterLength, afterWeight, now,
                request.operatorName());
        return new IssueResult(
                request.requestId(), true, false, "领料已登记", null,
                issue.getId(), order.getId(), order.getOrderNo(), roll.getId(), roll.getRollNo(),
                length, weight, afterLength, afterWeight, roll.getStatus(), issueView);
    }

    private String validateBusiness(CuttingOrder order, PaperRoll roll, Integer length, BigDecimal weight) {
        if (!"切裁中".equals(order.getStatus())) {
            return "只有「切裁中」的订单能登记领料，当前订单状态：" + order.getStatus();
        }
        if (order.getAssignedRoll() == null) {
            return "订单尚未排产母卷，不能登记领料";
        }
        if (!order.getAssignedRoll().getId().equals(roll.getId())) {
            return "母卷 " + roll.getRollNo() + " 不是订单 " + order.getOrderNo() + " 当前已排产的母卷";
        }
        if (!Dict.isRollUsable(roll.getStatus())) {
            return "母卷当前状态为「" + roll.getStatus() + "」，只有「可用」母卷能领料";
        }
        if (roll.getWidthMm() < order.getTargetWidthMm()) {
            return "规格已不匹配：母卷幅宽 " + roll.getWidthMm()
                    + "mm 小于订单目标幅宽 " + order.getTargetWidthMm() + "mm";
        }
        if (Math.abs(roll.getGrammageGsm() - order.getTargetGrammage()) > Dict.GRAMMAGE_TOLERANCE) {
            return "规格已不匹配：母卷定量 " + roll.getGrammageGsm()
                    + "g/m² 与订单目标 " + order.getTargetGrammage()
                    + "g/m² 偏差超过 " + Dict.GRAMMAGE_TOLERANCE;
        }
        if (length == null || length <= 0) {
            return "本次领料长度必须为正整数";
        }
        if (roll.getLengthM() == null || roll.getLengthM() < 0) {
            return "母卷剩余长度数据异常：" + roll.getLengthM() + " 米，请先核对台账";
        }
        if (weight == null || weight.signum() <= 0) {
            return "本次领料重量必须大于 0";
        }
        if (roll.getWeightKg() == null || roll.getWeightKg().signum() < 0) {
            return "母卷剩余重量数据异常：" + roll.getWeightKg() + "kg，请先核对台账";
        }
        if (weight.scale() > 2) {
            return "本次领料重量最多保留 2 位小数";
        }
        if (length > roll.getLengthM()) {
            return "领料长度 " + length + " 米超过母卷剩余长度 " + roll.getLengthM() + " 米";
        }
        if (weight.compareTo(roll.getWeightKg()) > 0) {
            return "领料重量 " + weight.setScale(2, java.math.RoundingMode.HALF_UP)
                    + "kg 超过母卷剩余重量 " + roll.getWeightKg() + "kg";
        }
        return null;
    }

    private IssueResult markFailed(CuttingIssueAttempt attempt,
                                   CuttingOrder order,
                                   PaperRoll roll,
                                   String reason) {
        if (order != null) {
            attempt.setOrderId(order.getId());
        }
        if (roll != null) {
            attempt.setRollId(roll.getId());
        }
        attempt.setState(CuttingIssueAttempt.STATE_FAILED);
        attempt.setFailureReason(truncate(reason, 512));
        attempt.setIssue(null);
        attempt.setFinishedAt(LocalDateTime.now());
        attemptRepository.save(attempt);

        CuttingOrder savedOrder = attempt.getOrderId() == null
                ? null : orderRepository.findById(attempt.getOrderId()).orElse(null);
        PaperRoll savedRoll = attempt.getRollId() == null
                ? null : rollRepository.findById(attempt.getRollId()).orElse(null);
        return IssueResult.failed(
                attempt.getRequestId(),
                attempt.getOrderId(),
                savedOrder == null ? null : savedOrder.getOrderNo(),
                attempt.getRollId(),
                savedRoll == null ? null : savedRoll.getRollNo(),
                attempt.getIssueLengthM(),
                attempt.getIssueWeightKg(),
                savedRoll == null ? null : savedRoll.getLengthM(),
                savedRoll == null ? null : savedRoll.getWeightKg(),
                savedRoll == null ? null : savedRoll.getStatus(),
                reason, false);
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
