package com.county.coldchain.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/** 货主处置确认：降级入库 / 现场退货 / 预冷后复检。 */
@Data
public class TempCaseDecideRequest {
    /** DOWNGRADE / RETURN / PRECOOL */
    @NotNull
    private String decision;
    /** 货主确认人（责任判定依据，必填） */
    @NotBlank
    private String decidedBy;
    private String decisionNote;
    /** 降级等级：B / PROCESS（DOWNGRADE 时） */
    private String downgradeGrade;
    /** 售价折扣率 0-1（DOWNGRADE 时，空则默认 0.2） */
    private BigDecimal priceDiscount;
    /** 降级后保鲜期天数（DOWNGRADE 时，空则默认 2 天） */
    private Integer shelfLifeDays;
    private String operator;
}
