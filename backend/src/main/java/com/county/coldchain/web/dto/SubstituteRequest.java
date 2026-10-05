package com.county.coldchain.web.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/** 撤回出库单的替代重排：销售员选择替代批次并向客户说明到货差异。 */
@Data
public class SubstituteRequest {
    /** 替代批次（须与原批次同品类、可售且未冻结） */
    @NotNull
    private Long batchId;
    @NotNull
    private List<OutboundRequest.Item> items;
    /** 向客户说明的到货差异（必填，随单留痕） */
    @NotNull
    private String note;
    /** 销售员 */
    private String operator;
}
