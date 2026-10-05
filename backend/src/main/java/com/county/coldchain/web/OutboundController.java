package com.county.coldchain.web;

import com.county.coldchain.domain.entity.OutboundItem;
import com.county.coldchain.domain.entity.OutboundOrder;
import com.county.coldchain.service.OutboundService;
import com.county.coldchain.web.dto.OutboundRequest;
import com.county.coldchain.web.dto.SubstituteRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "出库调度")
@RestController
@RequestMapping("/api/outbounds")
@RequiredArgsConstructor
public class OutboundController {

    private final OutboundService outboundService;

    @Operation(summary = "排单（临时改客户/优先出库自动告警，价格取锁价/货位价）")
    @PostMapping("/plan")
    public OutboundOrder plan(@Valid @RequestBody OutboundRequest req) {
        return outboundService.plan(req);
    }

    @Operation(summary = "执行出库（扣可售重量、释放库位、履约锁价）")
    @PostMapping("/{id}/execute")
    public OutboundOrder execute(@PathVariable Long id, @RequestParam(required = false) String operator) {
        return outboundService.execute(id, operator);
    }

    @Operation(summary = "替代排单：为已撤回出库单选择替代批次，并向客户说明到货差异")
    @PostMapping("/{id}/substitute")
    public OutboundOrder substitute(@PathVariable Long id, @Valid @RequestBody SubstituteRequest req) {
        return outboundService.substitute(id, req);
    }

    @Operation(summary = "出库单列表（PLANNED/OUT）")
    @GetMapping
    public List<OutboundOrder> list(@RequestParam(required = false) String status) {
        return outboundService.list(status);
    }

    @Operation(summary = "出库单明细")
    @GetMapping("/{id}/items")
    public List<OutboundItem> items(@PathVariable Long id) {
        return outboundService.items(id);
    }
}
