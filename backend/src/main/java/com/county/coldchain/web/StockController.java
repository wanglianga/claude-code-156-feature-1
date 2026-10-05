package com.county.coldchain.web;

import com.county.coldchain.domain.entity.StockLot;
import com.county.coldchain.domain.entity.StockMove;
import com.county.coldchain.domain.entity.Zone;
import com.county.coldchain.repo.StockMoveRepository;
import com.county.coldchain.service.StockService;
import com.county.coldchain.web.dto.LossRequest;
import com.county.coldchain.web.dto.PutAwayRequest;
import com.county.coldchain.web.dto.TransferRequest;
import com.county.coldchain.web.dto.ZoneStatusRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "库位温区库存")
@RestController
@RequestMapping("/api/stock")
@RequiredArgsConstructor
public class StockController {

    private final StockService stockService;
    private final StockMoveRepository moveRepository;

    @Operation(summary = "待上架货位批次")
    @GetMapping("/pending")
    public List<StockLot> pending() { return stockService.pendingLots(); }

    @Operation(summary = "在库货位批次")
    @GetMapping("/in-storage")
    public List<StockLot> inStorage() { return stockService.inStorageLots(); }

    @Operation(summary = "预冷暂存货位批次（复检前冻结销售）")
    @GetMapping("/precool")
    public List<StockLot> precool() { return stockService.precoolLots(); }

    @Operation(summary = "上架到库位")
    @PostMapping("/put-away")
    public StockLot putAway(@Valid @RequestBody PutAwayRequest req) {
        return stockService.putAway(req);
    }

    @Operation(summary = "转仓（温区故障/调度需要）")
    @PostMapping("/transfer")
    public StockLot transfer(@Valid @RequestBody TransferRequest req) {
        return stockService.transfer(req);
    }

    @Operation(summary = "库内报损（冷库/货主责任）")
    @PostMapping("/loss")
    public StockLot loss(@Valid @RequestBody LossRequest req) {
        return stockService.reportLoss(req);
    }

    @Operation(summary = "温区故障上报/解除（故障温区只出不进）")
    @PostMapping("/zones/{id}/status")
    public Zone zoneStatus(@PathVariable Long id, @RequestBody ZoneStatusRequest req) {
        return stockService.updateZoneStatus(id, req);
    }

    @Operation(summary = "批次库存移动流水")
    @GetMapping("/moves/batch/{batchId}")
    public List<StockMove> batchMoves(@PathVariable Long batchId) {
        return moveRepository.findByBatchIdOrderByMovedAtAsc(batchId);
    }
}
