package com.county.coldchain.domain.entity;

import com.county.coldchain.domain.BaseEntity;
import com.county.coldchain.domain.DomainEnums.OwnerDecision;
import com.county.coldchain.domain.DomainEnums.TempCaseStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 到货温度超标处置单：质检员记录到库温度、到货时间、车厢照片与抽检腐损率；
 * 货主确认降级入库 / 现场退货 / 预冷后复检；预冷复检前冻结批次销售，
 * 复检不合格自动撤回已分配出库计划。处置与确认记录作为投诉责任判定依据。
 */
@Getter
@Setter
@Entity
@Table(name = "biz_temp_case", indexes = {
        @Index(name = "idx_temp_case_no", columnList = "caseNo", unique = true),
        @Index(name = "idx_temp_case_batch", columnList = "batchId")
})
public class TempExceedCase extends BaseEntity {
    private String caseNo;
    private Long batchId;

    // ---- 质检员到库记录 ----
    /** 到库实测温度（℃） */
    private BigDecimal arrivalTemp;
    /** 合同要求温度下限/上限（℃），记录时按品类快照 */
    private BigDecimal contractMinTemp;
    private BigDecimal contractMaxTemp;
    /** 到货时间 */
    private LocalDateTime arrivedAt;
    /** 车厢照片（多个 URL 逗号分隔） */
    @Column(length = 2000)
    private String photoUrls;
    /** 抽检腐损率（0-1） */
    private BigDecimal sampleRotRate;
    /** 记录质检员 */
    private String inspector;
    private LocalDateTime recordedAt;

    // ---- 货主确认 ----
    @Enumerated(EnumType.STRING)
    private OwnerDecision ownerDecision;
    /** 货主确认人（合作社联系人） */
    private String decidedBy;
    private LocalDateTime decidedAt;
    /** 货主确认意见（责任判定依据） */
    @Column(length = 1000)
    private String decisionNote;

    // ---- 降级入库参数（影响保鲜期/售价） ----
    private String downgradeGrade;
    /** 售价折扣率（0-1，如 0.2 表示按基准价 8 折） */
    private BigDecimal priceDiscount;
    /** 降级后保鲜期（天） */
    private Integer shelfLifeDays;

    // ---- 预冷复检安排 ----
    /** 暂存温区 */
    private Long precoolZoneId;
    /** 复检时段 */
    private LocalDateTime recheckSlot;
    private LocalDateTime scheduledAt;
    /** 预冷暂存重量（公斤，占用温区容量与计收预冷费的口径） */
    private BigDecimal precoolWeight;

    // ---- 复检结果 ----
    private BigDecimal recheckTemp;
    private BigDecimal recheckRotRate;
    /** PASS / FAIL */
    private String recheckResult;
    private String recheckInspector;
    private LocalDateTime recheckedAt;
    private String recheckRemark;
    /** 预冷费（元，复检完成时按重量×费率核定，入账单） */
    private BigDecimal precoolFee = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    private TempCaseStatus status = TempCaseStatus.PENDING;
}
