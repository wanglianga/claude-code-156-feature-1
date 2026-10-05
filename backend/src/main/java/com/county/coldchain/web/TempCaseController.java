package com.county.coldchain.web;

import com.county.coldchain.domain.entity.TempExceedCase;
import com.county.coldchain.service.TempCaseService;
import com.county.coldchain.web.dto.RecheckRequest;
import com.county.coldchain.web.dto.TempCaseDecideRequest;
import com.county.coldchain.web.dto.TempCaseRecordRequest;
import com.county.coldchain.web.dto.TempCaseScheduleRequest;
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

@Tag(name = "温度超标处置")
@RestController
@RequestMapping("/api/temp-cases")
@RequiredArgsConstructor
public class TempCaseController {

    private final TempCaseService tempCaseService;

    @Operation(summary = "质检员登记到货温度超标（温度/到货时间/车厢照片/抽检腐损率）")
    @PostMapping
    public TempExceedCase record(@Valid @RequestBody TempCaseRecordRequest req) {
        return tempCaseService.record(req);
    }

    @Operation(summary = "货主确认处置：DOWNGRADE 降级入库 / RETURN 现场退货 / PRECOOL 预冷后复检")
    @PostMapping("/{id}/decide")
    public TempExceedCase decide(@PathVariable Long id, @Valid @RequestBody TempCaseDecideRequest req) {
        return tempCaseService.decide(id, req);
    }

    @Operation(summary = "平台安排预冷复检：复检时段 + 暂存温区（占用温区容量）")
    @PostMapping("/{id}/schedule")
    public TempExceedCase schedule(@PathVariable Long id, @Valid @RequestBody TempCaseScheduleRequest req) {
        return tempCaseService.schedule(id, req);
    }

    @Operation(summary = "登记复检结果：PASS 解除冻结 / FAIL 批次退货并自动撤回出库计划")
    @PostMapping("/{id}/recheck")
    public TempExceedCase recheck(@PathVariable Long id, @Valid @RequestBody RecheckRequest req) {
        return tempCaseService.recheck(id, req);
    }

    @Operation(summary = "处置单列表（可按批次/状态过滤）")
    @GetMapping
    public List<TempExceedCase> list(@RequestParam(required = false) Long batchId,
                                     @RequestParam(required = false) String status) {
        return tempCaseService.list(batchId, status);
    }

    @Operation(summary = "处置单详情")
    @GetMapping("/{id}")
    public TempExceedCase get(@PathVariable Long id) {
        return tempCaseService.get(id);
    }
}
