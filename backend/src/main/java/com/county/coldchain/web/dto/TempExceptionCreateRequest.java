package com.county.coldchain.web.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 温度超标处置登记（质检员）：到库温度、到货时间、车厢照片、抽检腐损率。 */
@Data
public class TempExceptionCreateRequest {
    @NotNull
    private Long batchId;
    /** 到库复测温度（℃） */
    @NotNull
    private BigDecimal measuredTemp;
    /** 到货时间（缺省取批次登记时间） */
    private LocalDateTime arrivedAt;
    /** 车厢照片 URL 列表 */
    private List<String> cabinPhotos;
    /** 抽检腐损率（0-1） */
    private BigDecimal sampledRotRate;
    private String inspector;
    private String remark;
}
