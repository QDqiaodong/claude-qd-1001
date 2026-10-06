package com.paper.mill.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.paper.mill.common.BizException;
import com.paper.mill.dto.CuttingIssueRequest;
import com.paper.mill.dto.IssueAttemptView;
import com.paper.mill.dto.IssueResult;
import com.paper.mill.dto.IssueTrace;
import com.paper.mill.dto.IssueView;
import com.paper.mill.entity.CuttingIssue;
import com.paper.mill.entity.CuttingIssueAttempt;
import com.paper.mill.entity.PaperRoll;
import com.paper.mill.repository.CuttingIssueAttemptRepository;
import com.paper.mill.repository.CuttingIssueRepository;
import com.paper.mill.repository.CuttingOrderRepository;
import com.paper.mill.repository.PaperRollRepository;

/**
 * 切纸工段 · 切裁领料闭环。
 *
 * <p>一次提交在同一事务内完成：校验订单 / 母卷 / 规格 / 余量、写成功领料记录、扣减母卷余量，
 * 必要时把母卷转为「已切完」。失败只写失败请求原因，不写领料记录、不扣余量。</p>
 */
@Service
public class CuttingIssueService {

    private static final int DUPLICATE_WAIT_MS = 19_000;

    private final CuttingIssueRepository issueRepository;
    private final CuttingIssueAttemptRepository attemptRepository;
    private final CuttingOrderRepository orderRepository;
    private final PaperRollRepository rollRepository;
    private final CuttingIssueTxService txService;

    public CuttingIssueService(CuttingIssueRepository issueRepository,
                               CuttingIssueAttemptRepository attemptRepository,
                               CuttingOrderRepository orderRepository,
                               PaperRollRepository rollRepository,
                               CuttingIssueTxService txService) {
        this.issueRepository = issueRepository;
        this.attemptRepository = attemptRepository;
        this.orderRepository = orderRepository;
        this.rollRepository = rollRepository;
        this.txService = txService;
    }

    /**
     * 提交领料。requestId 由前端为同一次点击保留，是重复点击 / 网络重试的幂等键。
     */
    public IssueResult issue(CuttingIssueRequest request) {
        validateEnvelope(request);
        String payloadHash = hash(request);

        CuttingIssueAttempt attempt;
        try {
            attempt = txService.createAttempt(request, payloadHash);
        } catch (DataIntegrityViolationException e) {
            return replayExisting(request, payloadHash);
        }
        return txService.process(attempt.getId());
    }

    @Transactional(readOnly = true)
    public IssueTrace traceOrder(Long orderId) {
        if (!orderRepository.existsById(orderId)) {
            throw new BizException("切纸订单不存在：" + orderId);
        }
        String orderNo = orderRepository.findById(orderId).orElseThrow().getOrderNo();
        List<IssueView> issues = issueRepository.findByOrderIdOrderByOperatedAtDesc(orderId)
                .stream().map(this::toIssueView).toList();
        List<IssueAttemptView> attempts = attemptRepository
                .findDetailedByOrderIdOrderByCreatedAtDesc(orderId)
                .stream().map(a -> toAttemptView(a, orderNo, null)).toList();
        return new IssueTrace(issues, attempts);
    }

    @Transactional(readOnly = true)
    public IssueTrace traceRoll(Long rollId) {
        if (!rollRepository.existsById(rollId)) {
            throw new BizException("纸卷不存在：" + rollId);
        }
        String rollNo = rollRepository.findById(rollId).orElseThrow().getRollNo();
        List<IssueView> issues = issueRepository.findByRollIdOrderByOperatedAtDesc(rollId)
                .stream().map(this::toIssueView).toList();
        List<IssueAttemptView> attempts = attemptRepository
                .findDetailedByRollIdOrderByCreatedAtDesc(rollId)
                .stream().map(a -> toAttemptView(a, null, rollNo)).toList();
        return new IssueTrace(issues, attempts);
    }

    private IssueResult replayExisting(CuttingIssueRequest request, String payloadHash) {
        long deadline = System.currentTimeMillis() + DUPLICATE_WAIT_MS;
        while (System.currentTimeMillis() <= deadline) {
            CuttingIssueAttempt existing = txService.lockAttempt(request.requestId().trim());
            if (!payloadHash.equals(existing.getPayloadHash())) {
                throw new BizException("请求号 " + request.requestId().trim() + " 已用于另一笔领料，不能重复使用");
            }
            if (existing.getState() != CuttingIssueAttempt.STATE_PROCESSING) {
                CuttingIssueAttempt detailed = attemptRepository
                        .findDetailedByRequestId(request.requestId().trim())
                        .orElseThrow(() -> new BizException("原领料结果不存在：" + request.requestId().trim()));
                return toResult(detailed, true);
            }
            sleep(50);
        }
        return IssueResult.failed(
                request.requestId().trim(), request.orderId(), null, request.rollId(), null,
                request.lengthM(), normalizeWeight(request.weightKg()), null, null, null,
                "原请求仍在处理中，请稍后刷新查询，请勿更换请求号重复提交", true);
    }

    private void validateEnvelope(CuttingIssueRequest request) {
        if (request == null) {
            throw new BizException("领料请求不能为空");
        }
        if (request.requestId() == null || request.requestId().isBlank()) {
            throw new BizException("缺少请求号，无法防止重复提交");
        }
        String requestId = request.requestId().trim();
        if (requestId.length() > 64) {
            throw new BizException("请求号长度不能超过 64 位");
        }
        String operator = request.operatorName() == null ? "" : request.operatorName().trim();
        if (operator.isEmpty()) {
            throw new BizException("操作人不能为空");
        }
        if (operator.length() > 32) {
            throw new BizException("操作人姓名不能超过 32 个字");
        }
    }

    private String hash(CuttingIssueRequest request) {
        String canonical = String.join("|",
                String.valueOf(request.orderId()),
                String.valueOf(request.rollId()),
                request.lengthM() == null ? "" : String.valueOf(request.lengthM()),
                request.weightKg() == null ? "" : normalizeWeight(request.weightKg()).toPlainString(),
                request.requestId().trim(),
                request.operatorName().trim());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", e);
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private IssueView toIssueView(CuttingIssue issue) {
        return new IssueView(
                issue.getId(),
                issue.getRequestId(),
                issue.getOrder().getId(),
                issue.getOrder().getOrderNo(),
                issue.getRoll().getId(),
                issue.getRoll().getRollNo(),
                issue.getIssueLengthM(),
                issue.getIssueWeightKg(),
                issue.getBeforeLengthM(),
                issue.getBeforeWeightKg(),
                issue.getAfterLengthM(),
                issue.getAfterWeightKg(),
                issue.getOperatedAt(),
                issue.getOperatorName());
    }

    private IssueAttemptView toAttemptView(CuttingIssueAttempt attempt,
                                           String knownOrderNo,
                                           String knownRollNo) {
        CuttingIssue issue = attempt.getIssue();
        Long orderId = attempt.getOrderId();
        String orderNo = knownOrderNo;
        Long rollId = attempt.getRollId();
        String rollNo = knownRollNo;
        if (orderId != null && orderNo == null) {
            orderNo = orderRepository.findById(orderId).map(o -> o.getOrderNo()).orElse(null);
        }
        if (rollId != null && rollNo == null) {
            rollNo = rollRepository.findById(rollId).map(r -> r.getRollNo()).orElse(null);
        }
        if (issue != null) {
            orderId = issue.getOrder().getId();
            orderNo = issue.getOrder().getOrderNo();
            rollId = issue.getRoll().getId();
            rollNo = issue.getRoll().getRollNo();
        }
        return new IssueAttemptView(
                attempt.getRequestId(),
                orderId,
                orderNo,
                rollId,
                rollNo,
                attempt.getIssueLengthM(),
                attempt.getIssueWeightKg(),
                attempt.getOperatorName(),
                switch (attempt.getState()) {
                    case CuttingIssueAttempt.STATE_SUCCESS -> "success";
                    case CuttingIssueAttempt.STATE_FAILED -> "failed";
                    default -> "processing";
                },
                attempt.getFailureReason(),
                issue == null ? null : issue.getId(),
                attempt.getCreatedAt(),
                attempt.getFinishedAt());
    }

    private IssueResult toResult(CuttingIssueAttempt attempt, boolean duplicate) {
        if (attempt.getState() == CuttingIssueAttempt.STATE_SUCCESS && attempt.getIssue() != null) {
            IssueView issueView = toIssueView(attempt.getIssue());
            return new IssueResult(
                    attempt.getRequestId(), true, duplicate,
                    duplicate ? "原领料已成功，本次为重复请求返回的原结果" : "领料已登记",
                    null, issueView.id(), issueView.orderId(), issueView.orderNo(),
                    issueView.rollId(), issueView.rollNo(), issueView.lengthM(), issueView.weightKg(),
                    issueView.afterLengthM(), issueView.afterWeightKg(), null, issueView);
        }
        PaperRoll roll = attempt.getRollId() == null ? null
                : rollRepository.findById(attempt.getRollId()).orElse(null);
        return IssueResult.failed(
                attempt.getRequestId(),
                attempt.getOrderId(),
                attempt.getOrderId() == null ? null
                        : orderRepository.findById(attempt.getOrderId()).map(o -> o.getOrderNo()).orElse(null),
                attempt.getRollId(),
                roll == null ? null : roll.getRollNo(),
                attempt.getIssueLengthM(),
                attempt.getIssueWeightKg(),
                roll == null ? null : roll.getLengthM(),
                roll == null ? null : roll.getWeightKg(),
                roll == null ? null : roll.getStatus(),
                attempt.getFailureReason(),
                duplicate);
    }

    private static BigDecimal normalizeWeight(BigDecimal weight) {
        return weight == null ? null : weight.setScale(2, RoundingMode.HALF_UP);
    }
}
