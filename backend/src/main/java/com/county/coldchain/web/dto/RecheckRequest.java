package com.county.coldchain.web.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/** 预冷后复检登记：默认按复测温度是否回到合同区间自动判定，可显式指定结论。 */
@Data
public class RecheckRequest {
    @NotNull
    private BigDecimal recheckTemp;
    private BigDecimal recheckRotRate;
    private String recheckBy;
    private String note;
    /** 合格结论（可空：空则按复测温度自动判定） */
    private Boolean passed;
}
