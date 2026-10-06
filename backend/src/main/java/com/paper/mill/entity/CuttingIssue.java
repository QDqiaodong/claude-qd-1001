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
 * 切纸工段 · 切裁领料成功记录。
 *
 * <p>记录与母卷余量扣减必须在同一个事务内提交；记录一旦生成，不随后续母卷状态变化删除。</p>
 */
@Entity
@Table(name = "cutting_issue")
@Getter
@Setter
public class CuttingIssue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 前端生成的幂等请求号，重复提交同一请求只能返回本条结果 */
    @Column(name = "request_id", nullable = false, unique = true, length = 64)
    private String requestId;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private CuttingOrder order;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "roll_id", nullable = false)
    private PaperRoll roll;

    @Column(name = "issue_length_m", nullable = false)
    private Integer issueLengthM;

    @Column(name = "issue_weight_kg", nullable = false, precision = 10, scale = 2)
    private BigDecimal issueWeightKg;

    @Column(name = "before_length_m", nullable = false)
    private Integer beforeLengthM;

    @Column(name = "before_weight_kg", nullable = false, precision = 10, scale = 2)
    private BigDecimal beforeWeightKg;

    @Column(name = "after_length_m", nullable = false)
    private Integer afterLengthM;

    @Column(name = "after_weight_kg", nullable = false, precision = 10, scale = 2)
    private BigDecimal afterWeightKg;

    @Column(name = "operated_at", nullable = false)
    private LocalDateTime operatedAt;

    @Column(name = "operator_name", nullable = false, length = 32)
    private String operatorName;
}
