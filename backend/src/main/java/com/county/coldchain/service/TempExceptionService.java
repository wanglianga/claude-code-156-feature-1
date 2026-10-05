package com.county.coldchain.service;

import com.county.coldchain.domain.DomainEnums.AlertLevel;
import com.county.coldchain.domain.DomainEnums.AlertType;
import com.county.coldchain.domain.DomainEnums.BatchStatus;
import com.county.coldchain.domain.DomainEnums.Grade;
import com.county.coldchain.domain.DomainEnums.MoveReason;
import com.county.coldchain.domain.DomainEnums.RecheckResult;
import com.county.coldchain.domain.DomainEnums.Stage;
import com.county.coldchain.domain.DomainEnums.TempDecision;
import com.county.coldchain.domain.DomainEnums.TempExceptionStatus;
import com.county.coldchain.domain.entity.Batch;
import com.county.coldchain.domain.entity.Category;
import com.county.coldchain.domain.entity.Location;
import com.county.coldchain.domain.entity.StockLot;
import com.county.coldchain.domain.entity.StockMove;
import com.county.coldchain.domain.entity.TempExceptionRecord;
import com.county.coldchain.domain.entity.Zone;
import com.county.coldchain.repo.BatchRepository;
import com.county.coldchain.repo.CategoryRepository;
import com.county.coldchain.repo.LocationRepository;
import com.county.coldchain.repo.StockLotRepository;
import com.county.coldchain.repo.StockMoveRepository;
import com.county.coldchain.repo.TempExceptionRepository;
import com.county.coldchain.repo.ZoneRepository;
import com.county.coldchain.support.BizException;
import com.county.coldchain.web.dto.RecheckRequest;
import com.county.coldchain.web.dto.TempDecisionRequest;
import com.county.coldchain.web.dto.TempExceptionCreateRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 到货温度超标处置：质检员记录到库温度/到货时间/车厢照片/抽检腐损率，
 * 货主抉择降级入库、现场退货或预冷后复检；不同选择折算保鲜期、仓储费与售价。
 * 预冷复检前冻结批次对外销售；复检不合格整批退货并自动撤回已排出库计划。
 */
@Service
@RequiredArgsConstructor
public class TempExceptionService {

    /** 正常保鲜期基准天数（折算口径，详见 README） */
    public static final int BASE_SHELF_LIFE_DAYS = 5;
    /** 降级入库保鲜期折算系数 */
    public static final double DOWNGRADE_SHELF_FACTOR = 0.5;
    /** 复检合格保鲜期折算系数 */
    public static final double RECHECK_SHELF_FACTOR = 0.7;
    /** 复检合格售价折算系数（相对 A 级价） */
    public static final BigDecimal RECHECK_PRICE_FACTOR = new BigDecimal("0.9");
    /** 预冷处理费 = 品类仓储费率 × 该系数（预冷期间加收） */
    public static final BigDecimal PRECOOL_FEE_FACTOR = new BigDecimal("0.5");

    private final TempExceptionRepository tempExceptionRepository;
    private final BatchRepository batchRepository;
    private final CategoryRepository categoryRepository;
    private final StockLotRepository stockLotRepository;
    private final StockMoveRepository stockMoveRepository;
    private final LocationRepository locationRepository;
    private final ZoneRepository zoneRepository;
    private final AlertService alertService;
    private final TraceService traceService;
    private final OutboundService outboundService;

    /** 质检员登记温度超标处置单。 */
    @Transactional
    public TempExceptionRecord create(TempExceptionCreateRequest req) {
        Batch batch = batchRepository.findById(req.getBatchId())
                .orElseThrow(() -> new BizException("批次不存在"));
        if (batch.getStatus() != BatchStatus.REGISTERED) {
            throw new BizException("仅待检批次可登记温度超标处置，当前状态：" + batch.getStatus().desc);
        }
        Category cat = categoryRepository.findById(batch.getCategoryId()).orElseThrow();
        boolean exceeded = exceeds(cat, req.getMeasuredTemp()) || exceeds(cat, batch.getArrivalTemp());
        if (!exceeded) {
            throw new BizException("到货温度与复测温度均在合同区间 ["
                    + cat.getMinArrivalTemp() + "," + cat.getMaxArrivalTemp() + "]℃ 内，无需超标处置");
        }
        boolean hasOpen = tempExceptionRepository.findByBatchIdOrderByCreatedAtDesc(batch.getId()).stream()
                .anyMatch(r -> r.getStatus() != TempExceptionStatus.CLOSED);
        if (hasOpen) {
            throw new BizException("该批次存在未办结的温度超标处置单，请先办结");
        }

        TempExceptionRecord r = new TempExceptionRecord();
        r.setRecordNo("T" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + String.format("%03d", tempExceptionRepository.count() + 1));
        r.setBatchId(batch.getId());
        r.setMeasuredTemp(req.getMeasuredTemp());
        r.setArrivedAt(req.getArrivedAt() == null ? batch.getArrivedAt() : req.getArrivedAt());
        r.setCabinPhotos(req.getCabinPhotos() == null ? null : String.join(",", req.getCabinPhotos()));
        r.setSampledRotRate(req.getSampledRotRate());
        r.setInspector(req.getInspector() == null ? "质检员" : req.getInspector());
        r.setRemark(req.getRemark());
        r.setContractMinTemp(cat.getMinArrivalTemp());
        r.setContractMaxTemp(cat.getMaxArrivalTemp());
        r.setStatus(TempExceptionStatus.PENDING_DECISION);
        r = tempExceptionRepository.save(r);

        alertService.raise(AlertType.TEMP_EXCEEDED, AlertLevel.WARN, batch.getId(), null,
                "温度超标待货主处置 " + batch.getBatchNo(),
                String.format("质检员复测 %s℃（合同区间 [%s,%s]℃），抽检腐损率 %s，等待货主抉择：降级入库 / 现场退货 / 预冷后复检。",
                        r.getMeasuredTemp(), cat.getMinArrivalTemp(), cat.getMaxArrivalTemp(),
                        percent(r.getSampledRotRate())),
                r.getInspector());
        traceService.log(batch.getId(), Stage.TEMP_EXCEPTION,
                "温度超标处置登记 " + r.getRecordNo(),
                String.format("复测温度 %s℃（合同 [%s,%s]℃），到货时间 %s，抽检腐损率 %s，车厢照片 %d 张。%s",
                        r.getMeasuredTemp(), cat.getMinArrivalTemp(), cat.getMaxArrivalTemp(),
                        r.getArrivedAt(), percent(r.getSampledRotRate()),
                        req.getCabinPhotos() == null ? 0 : req.getCabinPhotos().size(),
                        req.getRemark() == null ? "" : req.getRemark()),
                r.getInspector());
        return r;
    }

    /** 三种处置方式的影响预估（保鲜期/仓储费/售价），供货主抉择前查看。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> options(Long recordId) {
        TempExceptionRecord r = mustGet(recordId);
        Batch batch = batchRepository.findById(r.getBatchId()).orElseThrow();
        Category cat = categoryRepository.findById(batch.getCategoryId()).orElseThrow();
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(option(TempDecision.DOWNGRADE_ACCEPT, downgradeShelfDays(), cat.getStorageFeeRate(), null,
                cat.getDefaultPriceB(),
                String.format("按 B 级通货 %s 元/kg 入库（加工级 %s 元/kg），保鲜期 %d 天（正常 %d 天减半），仓储费 %s 元/kg·天不变",
                        cat.getDefaultPriceB(), cat.getDefaultPriceProcess(),
                        downgradeShelfDays(), BASE_SHELF_LIFE_DAYS, cat.getStorageFeeRate())));
        list.add(option(TempDecision.ONSITE_RETURN, 0, BigDecimal.ZERO, null, BigDecimal.ZERO,
                "整批现场退回货主，不入库、不产生仓储费与销售收入，批次关闭"));
        list.add(option(TempDecision.PRECOOL_RECHECK, recheckShelfDays(), cat.getStorageFeeRate(),
                precoolFeeRate(cat), recheckPrice(cat),
                String.format("先入暂存温区预冷，复检合格按 %s 元/kg（A级价9折）入库，保鲜期 %d 天（7折），"
                                + "预冷期间加收预冷处理费 %s 元/kg·天；复检不合格整批退货并自动撤回已排出库计划",
                        recheckPrice(cat), recheckShelfDays(), precoolFeeRate(cat))));
        return list;
    }

    /** 货主处置抉择（留痕确认人，投诉责任判定时回看）。 */
    @Transactional
    public TempExceptionRecord decide(Long recordId, TempDecisionRequest req) {
        TempExceptionRecord r = mustGet(recordId);
        if (r.getStatus() != TempExceptionStatus.PENDING_DECISION) {
            throw new BizException("处置单当前状态：" + r.getStatus().desc + "，不能重复抉择");
        }
        Batch batch = batchRepository.findById(r.getBatchId()).orElseThrow();
        if (batch.getStatus() != BatchStatus.REGISTERED) {
            throw new BizException("批次已进入后续环节（" + batch.getStatus().desc + "），不能按超标处置");
        }
        Category cat = categoryRepository.findById(batch.getCategoryId()).orElseThrow();
        TempDecision decision = TempDecision.valueOf(req.getDecision());
        if (decision != TempDecision.ONSITE_RETURN
                && batch.getQuarantine() != null && !Boolean.TRUE.equals(batch.getQuarantine().getVerified())) {
            throw new BizException("检疫票证未核验通过：除现场退货外不能入库或预冷");
        }

        r.setDecision(decision);
        r.setDecidedBy(req.getDecidedBy());
        r.setDecidedAt(LocalDateTime.now());
        r.setOwnerConfirmNote(req.getOwnerConfirmNote());
        BigDecimal net = batch.getNetWeight();

        switch (decision) {
            case DOWNGRADE_ACCEPT -> {
                Grade grade = req.getDowngradeGrade() == null ? Grade.B : Grade.valueOf(req.getDowngradeGrade());
                if (grade == Grade.A) {
                    throw new BizException("温度超标批次不能按 A 级入库");
                }
                BigDecimal price = grade == Grade.B ? cat.getDefaultPriceB() : cat.getDefaultPriceProcess();
                int shelf = downgradeShelfDays();
                r.setDowngradeGrade(grade);
                r.setShelfLifeDays(shelf);
                r.setStorageFeeRate(cat.getStorageFeeRate());
                r.setExpectedUnitPrice(price);
                r.setEffectNote(String.format("降级入库：%s %s 元/kg，保鲜期 %d 天，仓储费 %s 元/kg·天",
                        grade.desc, price, shelf, cat.getStorageFeeRate()));
                StockLot lot = createLot(batch, grade, net, price, shelf, "PENDING");
                batch.setAcceptedWeight(net);
                batch.setStatus(BatchStatus.INSPECTED);
                r.setStatus(TempExceptionStatus.CLOSED);
                traceService.log(batch.getId(), lot.getId(), null, Stage.TEMP_EXCEPTION,
                        "温度超标处置·货主选择降级入库",
                        String.format("货主 %s 确认知悉到货温度超标（复测 %s℃），%s 全量 %skg 按 %s 入库：%s。%s",
                                req.getDecidedBy(), r.getMeasuredTemp(), cat.getName(), net, grade.desc,
                                r.getEffectNote(), confirmSuffix(req)),
                        req.getDecidedBy());
            }
            case ONSITE_RETURN -> {
                r.setShelfLifeDays(0);
                r.setStorageFeeRate(BigDecimal.ZERO);
                r.setExpectedUnitPrice(BigDecimal.ZERO);
                r.setEffectNote("现场退货：整批退回货主，不产生仓储费与销售收入");
                doReturn(batch, net, req.getDecidedBy(), "温度超标现场退货：" + nz(req.getOwnerConfirmNote()));
                batch.setReturnedWeight(batch.getReturnedWeight().add(net));
                batch.setStatus(BatchStatus.RETURNED);
                r.setStatus(TempExceptionStatus.CLOSED);
                traceService.log(batch.getId(), Stage.TEMP_EXCEPTION,
                        "温度超标处置·货主选择现场退货",
                        String.format("货主 %s 现场确认整批退货 %skg（复测 %s℃ 超合同区间），批次关闭。%s",
                                req.getDecidedBy(), net, r.getMeasuredTemp(), confirmSuffix(req)),
                        req.getDecidedBy());
            }
            case PRECOOL_RECHECK -> {
                if (req.getPrecoolZoneId() == null) {
                    throw new BizException("请选择预冷暂存温区");
                }
                if (req.getRecheckSlotAt() == null) {
                    throw new BizException("请安排复检时段");
                }
                Zone zone = zoneRepository.findById(req.getPrecoolZoneId())
                        .orElseThrow(() -> new BizException("暂存温区不存在"));
                if (Boolean.TRUE.equals(zone.getFault())) {
                    throw new BizException("温区 " + zone.getName() + " 故障中，不能作为暂存温区");
                }
                Location loc = pickLocation(zone, net, req.getPrecoolLocationId());
                BigDecimal price = recheckPrice(cat);
                int shelf = recheckShelfDays();
                BigDecimal precoolFee = precoolFeeRate(cat);
                r.setPrecoolZoneId(zone.getId());
                r.setPrecoolLocationId(loc.getId());
                r.setRecheckSlotAt(req.getRecheckSlotAt());
                r.setShelfLifeDays(shelf);
                r.setStorageFeeRate(cat.getStorageFeeRate());
                r.setPrecoolFeeRate(precoolFee);
                r.setExpectedUnitPrice(price);
                r.setEffectNote(String.format("预冷后复检：暂存 %s，复检时段 %s；复检合格按 %s 元/kg、保鲜期 %d 天入库，"
                                + "预冷处理费 %s 元/kg·天；不合格整批退货并撤回出库计划",
                        zone.getName(), req.getRecheckSlotAt(), price, shelf, precoolFee));
                StockLot lot = createLot(batch, Grade.A, net, price, shelf, "PRECOOL");
                occupy(loc, zone, net);
                lot.setZoneId(zone.getId());
                lot.setLocationId(loc.getId());
                stockLotRepository.save(lot);
                r.setPrecoolLotId(lot.getId());
                batch.setStatus(BatchStatus.PRECOOLING);
                batch.setSaleFrozen(true);
                r.setStatus(TempExceptionStatus.PRECOOLING);
                alertService.raise(AlertType.TEMP_EXCEEDED, AlertLevel.WARN, batch.getId(), zone.getId(),
                        "批次预冷待复检，冻结对外销售 " + batch.getBatchNo(),
                        String.format("货主选择预冷后复检，暂存 %s/%s（%skg），复检时段 %s；复检合格前冻结该批次对外销售。",
                                zone.getName(), loc.getCode(), net, req.getRecheckSlotAt()),
                        req.getDecidedBy());
                traceService.log(batch.getId(), lot.getId(), null, Stage.TEMP_EXCEPTION,
                        "温度超标处置·货主选择预冷后复检",
                        String.format("货主 %s 确认预冷后复检：暂存温区 %s/%s，复检时段 %s，%s；复检前冻结对外销售。%s",
                                req.getDecidedBy(), zone.getName(), loc.getCode(), req.getRecheckSlotAt(),
                                r.getEffectNote(), confirmSuffix(req)),
                        req.getDecidedBy());
            }
        }
        batchRepository.save(batch);
        return tempExceptionRepository.save(r);
    }

    /** 预冷后复检登记：合格解冻转待上架；不合格整批退货并自动撤回已排出库计划。 */
    @Transactional
    public TempExceptionRecord recheck(Long recordId, RecheckRequest req) {
        TempExceptionRecord r = mustGet(recordId);
        if (r.getStatus() != TempExceptionStatus.PRECOOLING) {
            throw new BizException("处置单当前状态：" + r.getStatus().desc + "，不能复检");
        }
        Batch batch = batchRepository.findById(r.getBatchId()).orElseThrow();
        Category cat = categoryRepository.findById(batch.getCategoryId()).orElseThrow();
        StockLot lot = stockLotRepository.findById(r.getPrecoolLotId())
                .orElseThrow(() -> new BizException("预冷暂存货位批次不存在"));
        boolean passed = req.getPassed() != null ? req.getPassed() : !exceeds(cat, req.getRecheckTemp());
        String operator = req.getRecheckBy() == null ? "质检员" : req.getRecheckBy();

        r.setRecheckTemp(req.getRecheckTemp());
        r.setRecheckRotRate(req.getRecheckRotRate());
        r.setRecheckedAt(LocalDateTime.now());
        r.setRecheckBy(operator);
        r.setRecheckNote(req.getNote());
        r.setRecheckResult(passed ? RecheckResult.PASSED : RecheckResult.FAILED);
        r.setStatus(TempExceptionStatus.CLOSED);

        Location loc = locationRepository.findById(lot.getLocationId()).orElseThrow();
        Zone zone = zoneRepository.findById(lot.getZoneId()).orElseThrow();
        BigDecimal w = lot.getCurrentWeight();
        release(loc, zone, w);

        if (passed) {
            lot.setZoneId(null);
            lot.setLocationId(null);
            lot.setStatus("PENDING");
            stockLotRepository.save(lot);
            batch.setAcceptedWeight(w);
            batch.setStatus(BatchStatus.INSPECTED);
            batch.setSaleFrozen(false);
            traceService.log(batch.getId(), lot.getId(), null, Stage.TEMP_EXCEPTION,
                    "预冷复检合格·解除冻结",
                    String.format("复测 %s℃ 回到合同区间 [%s,%s]℃，腐损率 %s；批次解除销售冻结，%s 元/kg、保鲜期 %d 天，转待上架。%s",
                            req.getRecheckTemp(), cat.getMinArrivalTemp(), cat.getMaxArrivalTemp(),
                            percent(req.getRecheckRotRate()), r.getExpectedUnitPrice(), r.getShelfLifeDays(),
                            req.getNote() == null ? "" : req.getNote()),
                    operator);
        } else {
            lot.setStatus("RETURNED");
            stockLotRepository.save(lot);
            batch.setReturnedWeight(batch.getReturnedWeight().add(w));
            batch.setStatus(BatchStatus.RETURNED);
            batch.setSaleFrozen(false);
            doReturn(batch, w, operator, "预冷复检不合格整批退货：复测 " + req.getRecheckTemp() + "℃");
            traceService.log(batch.getId(), lot.getId(), null, Stage.TEMP_EXCEPTION,
                    "预冷复检不合格·整批退货",
                    String.format("复测 %s℃ 仍超合同区间 [%s,%s]℃，腐损率 %s；整批退货 %skg，批次关闭。%s",
                            req.getRecheckTemp(), cat.getMinArrivalTemp(), cat.getMaxArrivalTemp(),
                            percent(req.getRecheckRotRate()), w,
                            req.getNote() == null ? "" : req.getNote()),
                    operator);
            int withdrawn = outboundService.withdrawPlannedOrdersForBatch(batch.getId(),
                    "批次 " + batch.getBatchNo() + " 预冷复检不合格整批退货", operator);
            if (withdrawn > 0) {
                traceService.log(batch.getId(), Stage.TEMP_EXCEPTION,
                        "已分配出库计划自动撤回",
                        "共撤回 " + withdrawn + " 张已排单出库计划，待销售员选择替代批次重排并向客户说明到货差异。",
                        operator);
            }
        }
        batchRepository.save(batch);
        return tempExceptionRepository.save(r);
    }

    @Transactional(readOnly = true)
    public List<TempExceptionRecord> list(Long batchId, String status) {
        if (batchId != null) {
            return tempExceptionRepository.findByBatchIdOrderByCreatedAtDesc(batchId);
        }
        if (status != null && !status.isBlank()) {
            return tempExceptionRepository.findByStatusOrderByCreatedAtDesc(TempExceptionStatus.valueOf(status));
        }
        return tempExceptionRepository.findAllByOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public TempExceptionRecord get(Long id) {
        return mustGet(id);
    }

    private TempExceptionRecord mustGet(Long id) {
        return tempExceptionRepository.findById(id)
                .orElseThrow(() -> new BizException("温度超标处置单不存在: " + id));
    }

    private Map<String, Object> option(TempDecision d, int shelf, BigDecimal storageRate,
                                       BigDecimal precoolFee, BigDecimal price, String note) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("decision", d.name());
        m.put("decisionDesc", d.desc);
        m.put("shelfLifeDays", shelf);
        m.put("storageFeeRate", storageRate);
        m.put("precoolFeeRate", precoolFee);
        m.put("expectedUnitPrice", price);
        m.put("effectNote", note);
        return m;
    }

    private StockLot createLot(Batch batch, Grade grade, BigDecimal weight, BigDecimal price,
                               Integer shelfLifeDays, String status) {
        StockLot lot = new StockLot();
        lot.setLotNo("L" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + String.format("%03d", stockLotRepository.count() + 1));
        lot.setBatchId(batch.getId());
        lot.setGrade(grade);
        lot.setInitialWeight(weight);
        lot.setCurrentWeight(weight);
        lot.setUnitPrice(price);
        lot.setShelfLifeDays(shelfLifeDays);
        lot.setInAt(LocalDateTime.now());
        lot.setStatus(status);
        return stockLotRepository.save(lot);
    }

    private Location pickLocation(Zone zone, BigDecimal weight, Long locationId) {
        List<Location> candidates = locationRepository.findByZoneId(zone.getId());
        if (locationId != null) {
            Location loc = locationRepository.findById(locationId)
                    .orElseThrow(() -> new BizException("暂存库位不存在"));
            if (!loc.getZoneId().equals(zone.getId())) {
                throw new BizException("暂存库位不属于所选温区");
            }
            ensureCapacity(loc, zone, weight);
            return loc;
        }
        return candidates.stream()
                .filter(l -> l.getCapacityKg().subtract(l.getOccupiedKg()).compareTo(weight) >= 0)
                .findFirst()
                .orElseThrow(() -> new BizException("温区 " + zone.getName() + " 无可容暂存库位（需 "
                        + weight + "kg）"));
    }

    private void ensureCapacity(Location loc, Zone zone, BigDecimal w) {
        if (loc.getCapacityKg().subtract(loc.getOccupiedKg()).compareTo(w) < 0) {
            throw new BizException("库位 " + loc.getCode() + " 容量不足，可用 "
                    + loc.getCapacityKg().subtract(loc.getOccupiedKg()) + "kg");
        }
        if (zone.getCapacityKg().subtract(zone.getOccupiedKg()).compareTo(w) < 0) {
            throw new BizException("温区 " + zone.getName() + " 容量不足");
        }
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

    private void doReturn(Batch batch, BigDecimal weight, String operator, String remark) {
        StockMove m = new StockMove();
        m.setBatchId(batch.getId());
        m.setReason(MoveReason.RETURN);
        m.setWeight(weight);
        m.setOperator(operator);
        m.setMovedAt(LocalDateTime.now());
        m.setRemark(remark);
        stockMoveRepository.save(m);
    }

    static boolean exceeds(Category cat, BigDecimal temp) {
        if (temp == null) {
            return false;
        }
        return (cat.getMaxArrivalTemp() != null && temp.compareTo(cat.getMaxArrivalTemp()) > 0)
                || (cat.getMinArrivalTemp() != null && temp.compareTo(cat.getMinArrivalTemp()) < 0);
    }

    static int downgradeShelfDays() {
        return Math.max(1, (int) Math.floor(BASE_SHELF_LIFE_DAYS * DOWNGRADE_SHELF_FACTOR));
    }

    static int recheckShelfDays() {
        return Math.max(1, (int) Math.floor(BASE_SHELF_LIFE_DAYS * RECHECK_SHELF_FACTOR));
    }

    static BigDecimal recheckPrice(Category cat) {
        return cat.getDefaultPriceA().multiply(RECHECK_PRICE_FACTOR).setScale(2, RoundingMode.HALF_UP);
    }

    static BigDecimal precoolFeeRate(Category cat) {
        return cat.getStorageFeeRate().multiply(PRECOOL_FEE_FACTOR).setScale(4, RoundingMode.HALF_UP);
    }

    private String confirmSuffix(TempDecisionRequest req) {
        return req.getOwnerConfirmNote() == null || req.getOwnerConfirmNote().isBlank()
                ? "" : "货主确认备注：" + req.getOwnerConfirmNote();
    }

    private String nz(String s) { return s == null ? "" : s; }

    private String percent(BigDecimal v) {
        if (v == null) return "-";
        return v.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP) + "%";
    }
}
