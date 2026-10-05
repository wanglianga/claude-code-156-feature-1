package com.county.coldchain.domain.entity;

import com.county.coldchain.domain.BaseEntity;
import com.county.coldchain.domain.DomainEnums.Grade;
import com.county.coldchain.domain.DomainEnums.RecheckResult;
import com.county.coldchain.domain.DomainEnums.TempDecision;
import com.county.coldchain.domain.DomainEnums.TempExceptionStatus;
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
 * 到货温度超标处置单：质检员记录到库温度/到货时间/车厢照片/抽检腐损率，
 * 货主在三种处置方式中抉择（降级入库 / 现场退货 / 预冷后复检），
 * 不同选择折算不同的保鲜期、仓储费与后续售价；预冷复检不合格则整批退货并撤回出库计划。
 */
@Getter
@Setter
@Entity
@Table(name = "biz_temp_exception", indexes = {
        @Index(name = "idx_temp_exc_batch", columnList = "batchId"),
        @Index(name = "idx_temp_exc_status", columnList = "status")
})
public class TempExceptionRecord extends BaseEntity {
    private String recordNo;
    private Long batchId;

    // ---- 质检员到库记录 ----
    /** 到库复测温度（℃） */
    private BigDecimal measuredTemp;
    /** 到货时间 */
    private LocalDateTime arrivedAt;
    /** 车厢照片（多个 URL，逗号分隔） */
    private String cabinPhotos;
    /** 抽检腐损率（0-1） */
    private BigDecimal sampledRotRate;
    private String inspector;
    private String remark;
    /** 合同（品类档案）允许温度区间快照 */
    private BigDecimal contractMinTemp;
    private BigDecimal contractMaxTemp;

    // ---- 货主处置抉择 ----
    @Enumerated(EnumType.STRING)
    private TempDecision decision;
    /** 货主确认人（签字留痕，投诉责任判定时回看） */
    private String decidedBy;
    private LocalDateTime decidedAt;
    /** 货主确认备注（知悉温度超标仍选择入库等确认记录） */
    private String ownerConfirmNote;
    /** 降级入库时的目标等级 */
    @Enumerated(EnumType.STRING)
    private Grade downgradeGrade;

    // ---- 处置影响快照（不同选择影响保鲜期/仓储费/售价） ----
    /** 折算后保鲜期（天） */
    private Integer shelfLifeDays;
    /** 仓储费口径费率（元/kg·天，快照品类费率） */
    private BigDecimal storageFeeRate;
    /** 预冷处理费费率（元/kg·天，仅预冷复检路径） */
    private BigDecimal precoolFeeRate;
    /** 预计售价（元/kg） */
    private BigDecimal expectedUnitPrice;
    /** 影响说明（展示给货主与责任判定回看） */
    private String effectNote;

    // ---- 预冷后复检 ----
    private Long precoolZoneId;
    private Long precoolLocationId;
    /** 复检时段（平台重新安排的复检时间） */
    private LocalDateTime recheckSlotAt;
    /** 预冷暂存货位批次 */
    private Long precoolLotId;
    private BigDecimal recheckTemp;
    private BigDecimal recheckRotRate;
    private LocalDateTime recheckedAt;
    private String recheckBy;
    private String recheckNote;
    @Enumerated(EnumType.STRING)
    private RecheckResult recheckResult;

    @Enumerated(EnumType.STRING)
    private TempExceptionStatus status = TempExceptionStatus.PENDING_DECISION;
}
