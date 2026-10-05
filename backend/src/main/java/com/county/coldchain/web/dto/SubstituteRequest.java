package com.county.coldchain.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/** 复检不合格撤回后，销售员选择替代批次重新排单，并向客户说明到货差异。 */
@Data
public class SubstituteRequest {
    /** 替代批次 */
    @NotNull
    private Long batchId;
    @NotNull
    private List<OutboundRequest.Item> items;
    /** 向客户说明的到货差异（必填） */
    @NotBlank
    private String differenceNote;
    private Boolean priority;
    private String operator;
    private String remark;
}
