package com.county.coldchain.web.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 质检员登记到货温度超标：温度、到货时间、车厢照片、抽检腐损率。 */
@Data
public class TempCaseRecordRequest {
    @NotNull
    private Long batchId;
    /** 到库实测温度（℃） */
    @NotNull
    private BigDecimal arrivalTemp;
    /** 到货时间（空则取当前时间） */
    private LocalDateTime arrivedAt;
    /** 车厢照片 URL 列表 */
    private List<String> photoUrls;
    /** 抽检腐损率（0-1） */
    private BigDecimal sampleRotRate;
    private String inspector;
    private String remark;
}
