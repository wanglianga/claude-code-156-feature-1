package com.county.coldchain.domain.entity;

import com.county.coldchain.domain.BaseEntity;
import com.county.coldchain.domain.DomainEnums.OrderStatus;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 出库单：面向最终客户，一个出库单可包含同批次多个等级货位（也可跨批次）。 */
@Getter
@Setter
@Entity
@Table(name = "biz_outbound")
public class OutboundOrder extends BaseEntity {
    private String orderNo;
    private Long customerId;
    /** 实际客户可能临时改自批次计划客户 */
    private Long batchId;
    private Boolean customerChanged = false;
    private Long originalCustomerId;
    /** 客户要求优先出库 */
    private Boolean priority = false;
    private Boolean priceLocked = false;
    private BigDecimal lockedPrice;
    /** 出库总金额（元，按各明细实际计价） */
    private BigDecimal totalAmount = BigDecimal.ZERO;
    /** 出库总重量（公斤） */
    private BigDecimal totalWeight = BigDecimal.ZERO;
    @Enumerated(EnumType.STRING)
    private OrderStatus status = OrderStatus.PLANNED;
    /** 复检不合格等原因被自动撤回时的原因说明 */
    @jakarta.persistence.Column(length = 1000)
    private String withdrawReason;
    /** 本单为替代单时，指向被撤回的原出库单 */
    private Long replacesOrderId;
    /** 替代排单时向客户说明的到货差异 */
    @jakarta.persistence.Column(length = 1000)
    private String differenceNote;
    private String operator;
    private LocalDateTime plannedAt;
    private LocalDateTime outAt;
    private String remark;
}
