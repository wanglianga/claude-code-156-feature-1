package com.county.coldchain.web;

import com.county.coldchain.domain.entity.TempExceptionRecord;
import com.county.coldchain.service.TempExceptionService;
import com.county.coldchain.web.dto.RecheckRequest;
import com.county.coldchain.web.dto.TempDecisionRequest;
import com.county.coldchain.web.dto.TempExceptionCreateRequest;
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
import java.util.Map;

@Tag(name = "到货温度超标处置")
@RestController
@RequestMapping("/api/temp-exceptions")
@RequiredArgsConstructor
public class TempExceptionController {

    private final TempExceptionService tempExceptionService;

    @Operation(summary = "登记温度超标处置单（到库温度/到货时间/车厢照片/抽检腐损率）")
    @PostMapping
    public TempExceptionRecord create(@Valid @RequestBody TempExceptionCreateRequest req) {
        return tempExceptionService.create(req);
    }

    @Operation(summary = "三种处置方式影响预估（保鲜期/仓储费/售价）")
    @GetMapping("/{id}/options")
    public List<Map<String, Object>> options(@PathVariable Long id) {
        return tempExceptionService.options(id);
    }

    @Operation(summary = "货主处置抉择：DOWNGRADE_ACCEPT 降级入库 / ONSITE_RETURN 现场退货 / PRECOOL_RECHECK 预冷后复检")
    @PostMapping("/{id}/decision")
    public TempExceptionRecord decide(@PathVariable Long id, @Valid @RequestBody TempDecisionRequest req) {
        return tempExceptionService.decide(id, req);
    }

    @Operation(summary = "预冷后复检登记（合格解冻转待上架；不合格整批退货并自动撤回出库计划）")
    @PostMapping("/{id}/recheck")
    public TempExceptionRecord recheck(@PathVariable Long id, @Valid @RequestBody RecheckRequest req) {
        return tempExceptionService.recheck(id, req);
    }

    @Operation(summary = "处置单列表（可按批次/状态过滤）")
    @GetMapping
    public List<TempExceptionRecord> list(@RequestParam(required = false) Long batchId,
                                          @RequestParam(required = false) String status) {
        return tempExceptionService.list(batchId, status);
    }

    @Operation(summary = "处置单详情")
    @GetMapping("/{id}")
    public TempExceptionRecord get(@PathVariable Long id) {
        return tempExceptionService.get(id);
    }
}
