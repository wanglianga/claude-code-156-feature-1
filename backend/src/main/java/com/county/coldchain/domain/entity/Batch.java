package com.county.coldchain.domain.entity;

import com.county.coldchain.domain.BaseEntity;
import com.county.coldchain.domain.DomainEnums.BatchStatus;
import com.county.coldchain.domain.DomainEnums.SettleMethod;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 到货批次：批次链的源头。
 * 一辆车多个品类拆成多个批次，并通过 vehicleNo + mixedLoad 标记混批混装。
 */
@Getter
@Setter
@Entity
@Table(name = "biz_batch", indexes = {
        @Index(name = "idx_batch_no", columnList = "batchNo", unique = true),
        @Index(name = "idx_batch_status", columnList = "status")
})
public class Batch extends BaseEntity {
    private String batchNo;
    private Long ownerId;
    private Long categoryId;
    /** 产地（村镇） */
    private String origin;
    private LocalDate productionDate;
    /** 到货重量（公斤，原重） */
    private BigDecimal grossWeight;
    /** 皮重（公斤） */
    private BigDecimal tareWeight = BigDecimal.ZERO;
    /** 净重（公斤）= grossWeight - tareWeight，结算争议中的"原重"即净重 */
    private BigDecimal netWeight;
    /** 到货温度（℃） */
    private BigDecimal arrivalTemp;
    /** 包装方式：泡沫箱/筐装/散装/真空... */
    private String packaging;
    /** 检疫证明编号（票证） */
    private QuarantineInfo quarantine;
    /** 车牌号 */
    private String vehicleNo;
    /** 是否混批混装（同车多货主/多品类） */
    private Boolean mixedLoad = false;
    /** 预计出库客户（可被临时改客户覆盖） */
    private Long plannedCustomerId;
    @Enumerated(EnumType.STRING)
    private SettleMethod settleMethod;
    /** 客户锁价单价（元/公斤，可空） */
    private BigDecimal lockedPrice;
    /** 货主要求按原重结算（争议开关） */
    private Boolean settleByOriginalWeight = false;
    /** 温度超标选择预冷复检后、复检合格前冻结该批次对外销售 */
    private Boolean saleFrozen = false;
    @Enumerated(EnumType.STRING)
    private BatchStatus status = BatchStatus.REGISTERED;

    // ---- 质检后回写的可售口径 ----
    /** 累计入库可售重量（分拣各等级合计，公斤） */
    private BigDecimal acceptedWeight = BigDecimal.ZERO;
    /** 累计降级重量 */
    private BigDecimal downgradeWeight = BigDecimal.ZERO;
    /** 累计退货重量 */
    private BigDecimal returnedWeight = BigDecimal.ZERO;
    /** 累计报损重量 */
    private BigDecimal lossWeight = BigDecimal.ZERO;
    /** 累计已出库重量 */
    private BigDecimal outWeight = BigDecimal.ZERO;

    private LocalDateTime arrivedAt;
}
