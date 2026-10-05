package com.county.coldchain.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

/** 货主处置抉择：DOWNGRADE_ACCEPT 降级入库 / ONSITE_RETURN 现场退货 / PRECOOL_RECHECK 预冷后复检。 */
@Data
public class TempDecisionRequest {
    @NotNull
    private String decision;
    /** 货主确认人（签字留痕） */
    @NotBlank
    private String decidedBy;
    /** 货主确认备注（知悉温度超标等确认记录） */
    private String ownerConfirmNote;
    /** 降级入库目标等级：B / PROCESS */
    private String downgradeGrade;
    /** 预冷暂存温区（PRECOOL_RECHECK 必填） */
    private Long precoolZoneId;
    /** 预冷暂存库位（缺省自动分配温区内首个可容库位） */
    private Long precoolLocationId;
    /** 复检时段（PRECOOL_RECHECK 必填） */
    private LocalDateTime recheckSlotAt;
}
