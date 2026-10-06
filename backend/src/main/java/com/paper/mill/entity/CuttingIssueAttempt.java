package com.paper.mill.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 切裁领料请求的处理结果。
 *
 * <p>成功和失败都会留下请求轨迹；失败原因不与成功领料混在一起，刷新页面后仍可追溯。</p>
 */
@Entity
@Table(name = "cutting_issue_attempt")
@Getter
@Setter
public class CuttingIssueAttempt {

    public static final int STATE_PROCESSING = 0;
    public static final int STATE_SUCCESS = 1;
    public static final int STATE_FAILED = 2;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_id", nullable = false, unique = true, length = 64)
    private String requestId;

    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "roll_id")
    private Long rollId;

    @Column(name = "issue_length_m")
    private Integer issueLengthM;

    @Column(name = "issue_weight_kg", precision = 10, scale = 2)
    private BigDecimal issueWeightKg;

    @Column(name = "operator_name", length = 32)
    private String operatorName;

    /** 请求关键字段的规范哈希；同 requestId 但参数不同不能当成重复请求 */
    @Column(name = "payload_hash", nullable = false, length = 64)
    private String payloadHash;

    /** 0处理中，1成功，2失败 */
    @Column(name = "success", nullable = false)
    private Integer state;

    @Column(name = "failure_reason", length = 512)
    private String failureReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "issue_id")
    private CuttingIssue issue;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;
}
