package com.county.coldchain.web.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;

/** 预冷后复检结果登记。 */
@Data
public class RecheckRequest {
    private BigDecimal recheckTemp;
    private BigDecimal recheckRotRate;
    /** PASS / FAIL */
    @NotBlank
    private String result;
    private String inspector;
    private String remark;
}
