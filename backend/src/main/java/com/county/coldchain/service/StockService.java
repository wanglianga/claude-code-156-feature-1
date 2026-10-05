package com.county.coldchain.service;

import com.county.coldchain.domain.DomainEnums.AlertLevel;
import com.county.coldchain.domain.DomainEnums.AlertType;
import com.county.coldchain.domain.DomainEnums.BatchStatus;
import com.county.coldchain.domain.DomainEnums.MoveReason;
import com.county.coldchain.domain.DomainEnums.Responsibility;
import com.county.coldchain.domain.DomainEnums.Stage;
import com.county.coldchain.domain.entity.Batch;
import com.county.coldchain.domain.entity.Location;
import com.county.coldchain.domain.entity.StockLot;
import com.county.coldchain.domain.entity.StockMove;
import com.county.coldchain.domain.entity.Zone;
import com.county.coldchain.repo.AlertRepository;
import com.county.coldchain.repo.BatchRepository;
import com.county.coldchain.repo.LocationRepository;
import com.county.coldchain.repo.StockLotRepository;
import com.county.coldchain.repo.StockMoveRepository;
import com.county.coldchain.repo.ZoneRepository;
import com.county.coldchain.support.BizException;
import com.county.coldchain.web.dto.LossRequest;
import com.county.coldchain.web.dto.PutAwayRequest;
import com.county.coldchain.web.dto.TransferRequest;
import com.county.coldchain.web.dto.ZoneStatusRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 库位/温区库存操作：上架、转仓、报损；温区故障时冻结该温区进出。 */
@Service
@RequiredArgsConstructor
public class StockService {

    private final StockLotRepository lotRepository;
    private final LocationRepository locationRepository;
    private final ZoneRepository zoneRepository;
    private final StockMoveRepository moveRepository;
    private final BatchRepository batchRepository;
    private final AlertRepository alertRepository;
    private final AlertService alertService;
    private final TraceService traceService;

    @Transactional
    public StockLot putAway(PutAwayRequest req) {
        StockLot lot = lotRepository.findById(req.getLotId())
                .orElseThrow(() -> new BizException("货位批次不存在"));
        if (!"PENDING".equals(lot.getStatus())) {
            throw new BizException("该货位批次已上架，调整库位请使用转仓");
        }
        Location loc = locationRepository.findById(req.getLocationId())
                .orElseThrow(() -> new BizException("库位不存在"));
        Zone zone = zoneRepository.findById(loc.getZoneId()).orElseThrow();
        if (Boolean.TRUE.equals(zone.getFault())) {
            throw new BizException("温区 " + zone.getName() + " 故障中，禁止上架，请先转仓");
        }
        BigDecimal w = lot.getCurrentWeight();
        ensureCapacity(loc, zone, w, null);

        occupy(loc, zone, w);
        lot.setLocationId(loc.getId());
        lot.setZoneId(zone.getId());
        lot.setStatus("IN");
        lotRepository.save(lot);

        Batch batch = batchRepository.findById(lot.getBatchId()).orElseThrow();
        if (batch.getStatus() == BatchStatus.INSPECTED || batch.getStatus() == BatchStatus.REGISTERED) {
            batch.setStatus(BatchStatus.IN_STORAGE);
            batchRepository.save(batch);
        }
        move(lot, null, loc, null, zone, MoveReason.PUT_IN, w,
                req.getOperator(), "上架至 " + zone.getName() + "/" + loc.getCode()
                        + (req.getRemark() == null ? "" : "：" + req.getRemark()));
        traceService.log(batch.getId(), lot.getId(), null, Stage.PUT_IN,
                lot.getGrade().desc + " 上架 " + zone.getName() + "/" + loc.getCode(),
                "重量 " + w + "kg，目标温区 " + zone.getType().desc + "（设定 " + zone.getTargetTemp() + "℃）",
                req.getOperator());
        return lot;
    }

    @Transactional
    public StockLot transfer(TransferRequest req) {
        StockLot lot = lotRepository.findById(req.getLotId())
                .orElseThrow(() -> new BizException("货位批次不存在"));
        if (!"IN".equals(lot.getStatus())) {
            throw new BizException("非在库状态不能转仓");
        }
        Location from = locationRepository.findById(lot.getLocationId())
                .orElseThrow(() -> new BizException("原库位不存在"));
        Zone fromZone = zoneRepository.findById(lot.getZoneId()).orElseThrow();
        Location to = locationRepository.findById(req.getToLocationId())
                .orElseThrow(() -> new BizException("目标库位不存在"));
        Zone toZone = zoneRepository.findById(to.getZoneId()).orElseThrow();
        if (Boolean.TRUE.equals(toZone.getFault())) {
            throw new BizException("目标温区 " + toZone.getName() + " 故障中，不能转入");
        }
        if (to.getId().equals(from.getId())) {
            throw new BizException("目标库位与原库位相同");
        }
        BigDecimal w = lot.getCurrentWeight();
        ensureCapacity(to, toZone, w, null);
        release(from, fromZone, w);
        occupy(to, toZone, w);
        lot.setLocationId(to.getId());
        lot.setZoneId(toZone.getId());
        lotRepository.save(lot);

        move(lot, from, to, fromZone, toZone, MoveReason.TRANSFER, w,
                req.getOperator(), "转仓：" + fromZone.getName() + "/" + from.getCode() + " → "
                        + toZone.getName() + "/" + to.getCode()
                        + (req.getRemark() == null ? "" : "：" + req.getRemark()));
        traceService.log(lot.getBatchId(), lot.getId(), null, Stage.TRANSFER,
                "转仓 " + from.getCode() + " → " + to.getCode(),
                "温区 " + fromZone.getType().desc + " → " + toZone.getType().desc
                        + "，重量 " + w + "kg（转仓不改变账单归属，但影响后续仓储费温区单价口径由人工复核）",
                req.getOperator());
        return lot;
    }

    @Transactional
    public StockLot reportLoss(LossRequest req) {
        StockLot lot = lotRepository.findById(req.getLotId())
                .orElseThrow(() -> new BizException("货位批次不存在"));
        if (!"IN".equals(lot.getStatus())) {
            throw new BizException("非在库状态不能报损");
        }
        BigDecimal w = req.getWeight();
        if (w.signum() <= 0 || w.compareTo(lot.getCurrentWeight()) > 0) {
            throw new BizException("报损重量超出在库重量 " + lot.getCurrentWeight() + "kg");
        }
        Responsibility resp = Responsibility.valueOf(req.getResponsibility());
        Location loc = locationRepository.findById(lot.getLocationId()).orElseThrow();
        Zone zone = zoneRepository.findById(lot.getZoneId()).orElseThrow();
        release(loc, zone, w);

        lot.setCurrentWeight(lot.getCurrentWeight().subtract(w));
        lot.setLossWeight(lot.getLossWeight().add(w));
        if (lot.getCurrentWeight().signum() == 0) {
            lot.setStatus("LOST");
        }
        lotRepository.save(lot);

        Batch batch = batchRepository.findById(lot.getBatchId()).orElseThrow();
        batch.setLossWeight(batch.getLossWeight().add(w));
        batchRepository.save(batch);

        move(lot, loc, null, zone, null, MoveReason.LOSS, w, req.getOperator(),
                "报损 " + w + "kg，责任：" + resp.desc + (req.getRemark() == null ? "" : "：" + req.getRemark()));
        traceService.log(batch.getId(), lot.getId(), null, Stage.LOSS,
                lot.getGrade().desc + " 报损 " + w + "kg",
                "责任方：" + resp.desc + "。" + (resp == Responsibility.WAREHOUSE
                        ? "冷库责任报损将按货位单价补偿货主并计入账单。"
                        : "货主责任损耗由货主自行承担，不产生销售收入。"),
                req.getOperator());
        if (resp == Responsibility.WAREHOUSE) {
            alertService.raise(AlertType.LOSS_DISPUTE, AlertLevel.CRITICAL, batch.getId(), zone.getId(),
                    "冷库责任报损 " + batch.getBatchNo(),
                    lot.getGrade().desc + " 报损 " + w + "kg 判定冷库责任（温区/操作），财务需按 "
                            + lot.getUnitPrice() + " 元/kg 补偿货主。", req.getOperator());
        }
        return lot;
    }

    /** 温区故障上报/解除：故障温区只出不进，批次链自动告警并提示转仓。 */
    @Transactional
    public Zone updateZoneStatus(Long zoneId, ZoneStatusRequest req) {
        Zone zone = zoneRepository.findById(zoneId).orElseThrow(() -> new BizException("温区不存在"));
        if (req.getCurrentTemp() != null) {
            zone.setCurrentTemp(req.getCurrentTemp());
        }
        boolean nowFault = Boolean.TRUE.equals(req.getFault());
        if (nowFault && !Boolean.TRUE.equals(zone.getFault())) {
            zone.setFault(true);
            zone.setFaultNote(req.getFaultNote());
            List<StockLot> lots = lotRepository.findByZoneIdAndStatus(zoneId, "IN");
            StringBuilder batches = new StringBuilder();
            for (StockLot lot : lots) {
                batches.append(lot.getBatchId()).append(" ");
                alertService.raise(AlertType.ZONE_FAULT, AlertLevel.CRITICAL,
                        lot.getBatchId(), zoneId, "温区故障 " + zone.getName(),
                        (req.getFaultNote() == null ? "设备故障" : req.getFaultNote())
                                + "，受影响货位批次 " + lot.getLotNo() + "（" + lot.getGrade().desc
                                + " " + lot.getCurrentWeight() + "kg），请立即转仓或优先出库。",
                        "设备巡检");
            }
        } else if (!nowFault && Boolean.TRUE.equals(zone.getFault())) {
            zone.setFault(false);
            zone.setFaultNote(null);
            alertRepository.findByZoneIdAndResolvedFalse(zoneId)
                    .forEach(a -> alertService.resolve(a.getId(), "温区故障已修复，恢复正常运行"));
        }
        return zoneRepository.save(zone);
    }

    @Transactional(readOnly = true)
    public List<StockLot> pendingLots() {
        return lotRepository.findAll().stream().filter(l -> "PENDING".equals(l.getStatus())).toList();
    }

    @Transactional(readOnly = true)
    public List<StockLot> precoolLots() {
        return lotRepository.findByStatus("PRECOOL");
    }

    @Transactional(readOnly = true)
    public List<StockLot> inStorageLots() {
        return lotRepository.findByStatus("IN");
    }

    private void occupy(Location loc, Zone zone, BigDecimal w) {
        loc.setOccupiedKg(loc.getOccupiedKg().add(w));
        locationRepository.save(loc);
        zone.setOccupiedKg(zone.getOccupiedKg().add(w));
        zoneRepository.save(zone);
    }

    private void release(Location loc, Zone zone, BigDecimal w) {
        loc.setOccupiedKg(loc.getOccupiedKg().subtract(w).max(BigDecimal.ZERO));
        locationRepository.save(loc);
        zone.setOccupiedKg(zone.getOccupiedKg().subtract(w).max(BigDecimal.ZERO));
        zoneRepository.save(zone);
    }

    private void ensureCapacity(Location loc, Zone zone, BigDecimal w, Long currentLocationId) {
        BigDecimal locAvailable = loc.getCapacityKg().subtract(loc.getOccupiedKg())
                .add(currentLocationId != null && currentLocationId.equals(loc.getId()) ? w : BigDecimal.ZERO);
        if (locAvailable.compareTo(w) < 0) {
            throw new BizException("库位 " + loc.getCode() + " 容量不足，可用 " + locAvailable + "kg");
        }
        BigDecimal zoneAvailable = zone.getCapacityKg().subtract(zone.getOccupiedKg());
        if (zoneAvailable.compareTo(w) < 0) {
            throw new BizException("温区 " + zone.getName() + " 容量不足，可用 " + zoneAvailable + "kg");
        }
    }

    private void move(StockLot lot, Location from, Location to, Zone fz, Zone tz,
                      MoveReason reason, BigDecimal w, String operator, String remark) {
        StockMove m = new StockMove();
        m.setBatchId(lot.getBatchId());
        m.setLotId(lot.getId());
        m.setFromLocationId(from == null ? null : from.getId());
        m.setToLocationId(to == null ? null : to.getId());
        m.setFromZoneId(fz == null ? null : fz.getId());
        m.setToZoneId(tz == null ? null : tz.getId());
        m.setReason(reason);
        m.setWeight(w);
        m.setOperator(operator == null ? "系统" : operator);
        m.setMovedAt(LocalDateTime.now());
        m.setRemark(remark);
        moveRepository.save(m);
    }
}
