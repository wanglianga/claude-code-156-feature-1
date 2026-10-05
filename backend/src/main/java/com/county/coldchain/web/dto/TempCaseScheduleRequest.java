package com.county.coldchain.web.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

/** 平台安排预冷复检：复检时段 + 暂存温区。 */
@Data
public class TempCaseScheduleRequest {
    /** 复检时段 */
    @NotNull
    private LocalDateTime recheckSlot;
    /** 暂存温区 */
    @NotNull
    private Long zoneId;
    private String operator;
}
