package com.county.coldchain.service;

import com.county.coldchain.domain.DomainEnums.AlertLevel;
import com.county.coldchain.domain.DomainEnums.AlertType;
import com.county.coldchain.domain.DomainEnums.BatchStatus;
import com.county.coldchain.domain.DomainEnums.Grade;
import com.county.coldchain.domain.DomainEnums.MoveReason;
import com.county.coldchain.domain.DomainEnums.OwnerDecision;
import com.county.coldchain.domain.DomainEnums.Stage;
import com.county.coldchain.domain.DomainEnums.TempCaseStatus;
import com.county.coldchain.domain.entity.Batch;
import com.county.coldchain.domain.entity.Category;
import com.county.coldchain.domain.entity.Location;
import com.county.coldchain.domain.entity.StockLot;
import com.county.coldchain.domain.entity.StockMove;
import com.county.coldchain.domain.entity.TempExceedCase;
import com.county.coldchain.domain.entity.Zone;
import com.county.coldchain.repo.BatchRepository;
import com.county.coldchain.repo.CategoryRepository;
import com.county.coldchain.repo.LocationRepository;
import com.county.coldchain.repo.StockLotRepository;
import com.county.coldchain.repo.StockMoveRepository;
import com.county.coldchain.repo.TempExceedCaseRepository;
import com.county.coldchain.repo.ZoneRepository;
import com.county.coldchain.support.BizException;
import com.county.coldchain.web.dto.RecheckRequest;
import com.county.coldchain.web.dto.TempCaseDecideRequest;
import com.county.coldchain.web.dto.TempCaseRecordRequest;
import com.county.coldchain.web.dto.TempCaseScheduleRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.List;

/**
 * 到货温度超标处置：质检员记录（温度/到货时间/车厢照片/抽检腐损率）→
 * 货主确认（降级入库 / 现场退货 / 预冷后复检）→ 预冷复检安排与结果。
 * 不同选择影响保鲜期、仓储费（预冷费）与后续售价；复检不合格自动撤回已分配出库计划。
 */
@Service
@RequiredArgsConstructor
public class TempCaseService {

    private final TempExceedCaseRepository caseRepository;
    private final BatchRepository batchRepository;
    private final CategoryRepository categoryRepository;
    private final StockLotRepository lotRepository;
    private final StockMoveRepository moveRepository;
    private final LocationRepository locationRepository;
    private final ZoneRepository zoneRepository;
    private final AlertService alertService;
    private final TraceService traceService;
    private final OutboundService outboundService;

    /** 预冷费单价（元/公斤），见 application.yml app.precool.fee-per-kg */
    @Value("${app.precool.fee-per-kg:0.3}")
    private BigDecimal precoolFeeRate;

    private static final EnumSet<TempCaseStatus> OPEN_STATUSES = EnumSet.of(
            TempCaseStatus.PENDING, TempCaseStatus.PRECOOLING, TempCaseStatus.RECHECK_SCHEDULED);

    /** 质检员登记温度超标：温度、到货时间、车厢照片、抽检腐损率。 */
    @Transactional
    public TempExceedCase record(TempCaseRecordRequest req) {
        Batch batch = batchRepository.findById(req.getBatchId())
                .orElseThrow(() -> new BizException("批次不存在"));
        if (batch.getStatus() == BatchStatus.RETURNED || batch.getStatus() == BatchStatus.OUT) {
            throw new BizException("批次已关闭（" + batch.getStatus().desc + "），不能登记温度超标处置");
        }
        Category cat = categoryRepository.findById(batch.getCategoryId()).orElseThrow();
        boolean exceeded = (cat.getMaxArrivalTemp() != null
                && req.getArrivalTemp().compareTo(cat.getMaxArrivalTemp()) > 0)
                || (cat.getMinArrivalTemp() != null
                && req.getArrivalTemp().compareTo(cat.getMinArrivalTemp()) < 0);
        if (!exceeded) {
            throw new BizException("到库温度 " + req.getArrivalTemp() + "℃ 未超出合同要求区间 ["
                    + cat.getMinArrivalTemp() + ", " + cat.getMaxArrivalTemp() + "]℃，无需登记超标处置");
        }
        boolean hasOpen = caseRepository.findByBatchIdOrderByCreatedAtDesc(batch.getId()).stream()
                .anyMatch(c -> OPEN_STATUSES.contains(c.getStatus()));
        if (hasOpen) {
            throw new BizException("该批次已有未办结的温度超标处置单");
        }

        TempExceedCase c = new TempExceedCase();
        c.setCaseNo("TC" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + String.format("%03d", caseRepository.maxId() + 1));
        c.setBatchId(batch.getId());
        c.setArrivalTemp(req.getArrivalTemp());
        c.setContractMinTemp(cat.getMinArrivalTemp());
        c.setContractMaxTemp(cat.getMaxArrivalTemp());
        c.setArrivedAt(req.getArrivedAt() == null ? LocalDateTime.now() : req.getArrivedAt());
        c.setPhotoUrls(req.getPhotoUrls() == null ? null : String.join(",", req.getPhotoUrls()));
        c.setSampleRotRate(req.getSampleRotRate());
        c.setInspector(req.getInspector() == null ? "质检员" : req.getInspector());
        c.setRecordedAt(LocalDateTime.now());
        c.setStatus(TempCaseStatus.PENDING);
        c = caseRepository.save(c);

        alertService.raise(AlertType.TEMP_EXCEEDED, AlertLevel.CRITICAL, batch.getId(), null,
                "温度超标待货主决策 " + batch.getBatchNo(),
                String.format("处置单 %s：到库 %s℃ 超合同区间 [%s,%s]℃，抽检腐损率 %s，请货主确认降级入库/现场退货/预冷后复检。",
                        c.getCaseNo(), c.getArrivalTemp(), cat.getMinArrivalTemp(), cat.getMaxArrivalTemp(),
                        c.getSampleRotRate() == null ? "未抽检" : c.getSampleRotRate()
                                .multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP) + "%"),
                c.getInspector());
        traceService.log(batch.getId(), Stage.TEMP_HANDLING, "温度超标记录 " + c.getCaseNo(),
                String.format("质检员 %s 记录：到库温度 %s℃（合同 [%s,%s]℃），到货时间 %s，抽检腐损率 %s，车厢照片 %d 张。%s",
                        c.getInspector(), c.getArrivalTemp(), cat.getMinArrivalTemp(), cat.getMaxArrivalTemp(),
                        c.getArrivedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
                        c.getSampleRotRate() == null ? "未抽检" : c.getSampleRotRate()
                                .multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP) + "%",
                        c.getPhotoUrls() == null || c.getPhotoUrls().isBlank()
                                ? 0 : c.getPhotoUrls().split(",").length,
                        req.getRemark() == null ? "" : req.getRemark()),
                c.getInspector());
        return c;
    }

    /** 货主确认处置方式：降级入库 / 现场退货 / 预冷后复检。 */
    @Transactional
    public TempExceedCase decide(Long id, TempCaseDecideRequest req) {
        TempExceedCase c = mustGet(id);
        if (c.getStatus() != TempCaseStatus.PENDING) {
            throw new BizException("处置单当前状态为「" + c.getStatus().desc + "」，不能重复决策");
        }
        Batch batch = batchRepository.findById(c.getBatchId()).orElseThrow();
        Category cat = categoryRepository.findById(batch.getCategoryId()).orElseThrow();
        OwnerDecision decision = OwnerDecision.valueOf(req.getDecision());
        String operator = req.getOperator() == null ? req.getDecidedBy() : req.getOperator();

        c.setOwnerDecision(decision);
        c.setDecidedBy(req.getDecidedBy());
        c.setDecidedAt(LocalDateTime.now());
        c.setDecisionNote(req.getDecisionNote());

        switch (decision) {
            case DOWNGRADE -> applyDowngrade(c, batch, cat, req, operator);
            case RETURN -> applyReturn(c, batch, operator);
            case PRECOOL -> applyPrecool(c, batch, operator);
        }
        caseRepository.save(c);
        batchRepository.save(batch);
        return c;
    }

    /** 降级入库：保鲜期缩短、售价折让，仓储费按正常费率计。 */
    private void applyDowngrade(TempExceedCase c, Batch batch, Category cat,
                                TempCaseDecideRequest req, String operator) {
        if (batch.getStatus() != BatchStatus.REGISTERED) {
            throw new BizException("批次已质检（" + batch.getStatus().desc
                    + "），无法按超标处置降级入库，请走分拣/报损流程");
        }
        Grade grade = req.getDowngradeGrade() == null ? Grade.B : Grade.valueOf(req.getDowngradeGrade());
        if (grade == Grade.A) {
            throw new BizException("温度超标降级入库等级不能为 A 级");
        }
        BigDecimal discount = req.getPriceDiscount() == null
                ? new BigDecimal("0.2") : req.getPriceDiscount();
        if (discount.signum() < 0 || discount.compareTo(BigDecimal.ONE) >= 0) {
            throw new BizException("售价折扣率须在 [0,1) 之间");
        }
        int shelfLifeDays = req.getShelfLifeDays() == null ? 2 : req.getShelfLifeDays();
        BigDecimal base = grade == Grade.B ? cat.getDefaultPriceB() : cat.getDefaultPriceProcess();
        BigDecimal price = base.multiply(BigDecimal.ONE.subtract(discount))
                .setScale(2, RoundingMode.HALF_UP);

        StockLot lot = new StockLot();
        lot.setLotNo("L" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + String.format("%03d", lotRepository.count() + 1));
        lot.setBatchId(batch.getId());
        lot.setGrade(grade);
        lot.setInitialWeight(batch.getNetWeight());
        lot.setCurrentWeight(batch.getNetWeight());
        lot.setUnitPrice(price);
        lot.setShelfLifeDays(shelfLifeDays);
        lot.setInAt(LocalDateTime.now());
        lot.setStatus("PENDING");
        lotRepository.save(lot);

        batch.setAcceptedWeight(batch.getNetWeight());
        batch.setStatus(BatchStatus.INSPECTED);

        c.setDowngradeGrade(grade.name());
        c.setPriceDiscount(discount);
        c.setShelfLifeDays(shelfLifeDays);
        c.setStatus(TempCaseStatus.DOWNGRADED);

        traceService.log(batch.getId(), lot.getId(), null, Stage.TEMP_HANDLING,
                "货主确认降级入库 " + c.getCaseNo(),
                String.format("货主「%s」确认温度超标降级入库：%s %skg，售价折让 %s（基准 %s → %s 元/kg），"
                                + "保鲜期缩短为 %d 天，仓储费按正常费率计。%s",
                        c.getDecidedBy(), grade.desc, batch.getNetWeight(),
                        discount.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP) + "%",
                        base, price, shelfLifeDays,
                        c.getDecisionNote() == null ? "" : "确认意见：" + c.getDecisionNote()),
                operator);
    }

    /** 现场退货：批次关闭，不产生保鲜期/仓储费/售价；已分配出库计划同步撤回。 */
    private void applyReturn(TempExceedCase c, Batch batch, String operator) {
        returnAllRemaining(batch, "温度超标货主确认现场退货", operator);
        int withdrawn = outboundService.withdrawPlannedOrders(batch.getId(),
                "温度超标货主确认现场退货", operator);
        batch.setStatus(BatchStatus.RETURNED);
        batch.setSaleFrozen(false);
        c.setStatus(TempCaseStatus.RETURNED);
        traceService.log(batch.getId(), Stage.TEMP_HANDLING, "货主确认现场退货 " + c.getCaseNo(),
                String.format("货主「%s」确认现场退货，批次关闭，不产生仓储/分拣费用，无后续售价。%s%s",
                        c.getDecidedBy(),
                        withdrawn > 0 ? "已自动撤回出库计划 " + withdrawn + " 单。" : "",
                        c.getDecisionNote() == null ? "" : "确认意见：" + c.getDecisionNote()),
                operator);
    }

    /** 预冷后复检：冻结批次对外销售，等待平台安排复检时段与暂存温区。 */
    private void applyPrecool(TempExceedCase c, Batch batch, String operator) {
        if (batch.getStatus() == BatchStatus.RETURNED || batch.getStatus() == BatchStatus.OUT) {
            throw new BizException("批次已关闭，不能选择预冷复检");
        }
        batch.setSaleFrozen(true);
        c.setStatus(TempCaseStatus.PRECOOLING);
        traceService.log(batch.getId(), Stage.TEMP_HANDLING, "货主确认预冷后复检 " + c.getCaseNo(),
                String.format("货主「%s」选择先预冷再复检：批次已冻结对外销售（禁止排单与出库），"
                                + "待平台安排复检时段与暂存温区；预冷将按重量计收预冷费。%s",
                        c.getDecidedBy(),
                        c.getDecisionNote() == null ? "" : "确认意见：" + c.getDecisionNote()),
                operator);
    }

    /** 平台安排复检时段与暂存温区，暂存重量占用温区容量。 */
    @Transactional
    public TempExceedCase schedule(Long id, TempCaseScheduleRequest req) {
        TempExceedCase c = mustGet(id);
        if (c.getStatus() != TempCaseStatus.PRECOOLING) {
            throw new BizException("处置单当前状态为「" + c.getStatus().desc + "」，不能安排复检");
        }
        Batch batch = batchRepository.findById(c.getBatchId()).orElseThrow();
        Zone zone = zoneRepository.findById(req.getZoneId())
                .orElseThrow(() -> new BizException("暂存温区不存在"));
        if (Boolean.TRUE.equals(zone.getFault())) {
            throw new BizException("温区 " + zone.getName() + " 故障中，不能作为暂存温区");
        }
        BigDecimal weight = remainingWeight(batch);
        if (weight.signum() <= 0) {
            throw new BizException("批次无可预冷暂存的剩余重量");
        }
        BigDecimal available = zone.getCapacityKg().subtract(zone.getOccupiedKg());
        if (available.compareTo(weight) < 0) {
            throw new BizException("温区 " + zone.getName() + " 容量不足，可用 " + available + "kg，需 " + weight + "kg");
        }
        zone.setOccupiedKg(zone.getOccupiedKg().add(weight));
        zoneRepository.save(zone);

        c.setPrecoolZoneId(zone.getId());
        c.setPrecoolWeight(weight);
        c.setRecheckSlot(req.getRecheckSlot());
        c.setScheduledAt(LocalDateTime.now());
        c.setStatus(TempCaseStatus.RECHECK_SCHEDULED);
        caseRepository.save(c);

        String operator = req.getOperator() == null ? "调度员" : req.getOperator();
        traceService.log(batch.getId(), Stage.TEMP_HANDLING, "安排预冷复检 " + c.getCaseNo(),
                String.format("复检时段 %s，暂存温区 %s（%s，设定 %s℃），暂存重量 %skg；复检前批次持续冻结销售。",
                        req.getRecheckSlot().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
                        zone.getName(), zone.getType().desc, zone.getTargetTemp(), weight),
                operator);
        return c;
    }

    /** 复检登记：合格解除冻结；不合格批次退货关闭并自动撤回已分配出库计划。 */
    @Transactional
    public TempExceedCase recheck(Long id, RecheckRequest req) {
        TempExceedCase c = mustGet(id);
        if (c.getStatus() != TempCaseStatus.RECHECK_SCHEDULED) {
            throw new BizException("处置单当前状态为「" + c.getStatus().desc + "」，不能登记复检结果");
        }
        Batch batch = batchRepository.findById(c.getBatchId()).orElseThrow();
        Category cat = categoryRepository.findById(batch.getCategoryId()).orElseThrow();
        String result = req.getResult().toUpperCase();
        if (!"PASS".equals(result) && !"FAIL".equals(result)) {
            throw new BizException("复检结果只能是 PASS / FAIL");
        }
        String inspector = req.getInspector() == null ? "质检员" : req.getInspector();

        // 释放暂存温区占用
        if (c.getPrecoolZoneId() != null && c.getPrecoolWeight() != null) {
            zoneRepository.findById(c.getPrecoolZoneId()).ifPresent(z -> {
                z.setOccupiedKg(z.getOccupiedKg().subtract(c.getPrecoolWeight()).max(BigDecimal.ZERO));
                zoneRepository.save(z);
            });
        }
        // 预冷费：无论复检合格与否，预冷已发生即计收
        BigDecimal fee = (c.getPrecoolWeight() == null ? BigDecimal.ZERO : c.getPrecoolWeight())
                .multiply(precoolFeeRate).setScale(2, RoundingMode.HALF_UP);
        c.setPrecoolFee(fee);
        c.setRecheckTemp(req.getRecheckTemp());
        c.setRecheckRotRate(req.getRecheckRotRate());
        c.setRecheckResult(result);
        c.setRecheckInspector(inspector);
        c.setRecheckedAt(LocalDateTime.now());
        c.setRecheckRemark(req.getRemark());

        if ("PASS".equals(result)) {
            c.setStatus(TempCaseStatus.RECHECK_PASS);
            batch.setSaleFrozen(false);
            traceService.log(batch.getId(), Stage.TEMP_HANDLING, "复检合格解除冻结 " + c.getCaseNo(),
                    String.format("复检温度 %s℃、腐损率 %s，判定合格：解除批次销售冻结，可正常排单出库；"
                                    + "预冷费 %s 元（%skg × %s 元/kg）计入账单。%s",
                            req.getRecheckTemp(),
                            req.getRecheckRotRate() == null ? "未抽检" : req.getRecheckRotRate()
                                    .multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP) + "%",
                            fee, c.getPrecoolWeight(), precoolFeeRate,
                            req.getRemark() == null ? "" : req.getRemark()),
                    inspector);
        } else {
            c.setStatus(TempCaseStatus.RECHECK_FAIL);
            returnAllRemaining(batch, "预冷复检不合格（温度 " + req.getRecheckTemp() + "℃）", inspector);
            int withdrawn = outboundService.withdrawPlannedOrders(batch.getId(),
                    "温度超标预冷复检不合格", inspector);
            batch.setStatus(BatchStatus.RETURNED);
            batch.setSaleFrozen(false);
            alertService.raise(AlertType.RECHECK_FAILED, AlertLevel.CRITICAL, batch.getId(), null,
                    "预冷复检不合格 " + batch.getBatchNo(),
                    String.format("处置单 %s 复检温度 %s℃ 仍超合同区间 [%s,%s]℃，批次退货关闭；"
                                    + "已自动撤回出库计划 %d 单，销售员需重新选择替代批次并向客户说明到货差异。",
                            c.getCaseNo(), req.getRecheckTemp(),
                            cat.getMinArrivalTemp(), cat.getMaxArrivalTemp(), withdrawn),
                    inspector);
            traceService.log(batch.getId(), Stage.TEMP_HANDLING, "复检不合格批次关闭 " + c.getCaseNo(),
                    String.format("复检温度 %s℃ 仍超标，判定不合格：批次退货关闭；自动撤回已分配出库计划 %d 单；"
                                    + "预冷费 %s 元仍计入账单。%s",
                            req.getRecheckTemp(), withdrawn, fee,
                            req.getRemark() == null ? "" : req.getRemark()),
                    inspector);
        }
        caseRepository.save(c);
        batchRepository.save(batch);
        return c;
    }

    /** 退回批次全部剩余货物：释放在库/待上架货位与库容，记退货流水。 */
    private void returnAllRemaining(Batch batch, String remark, String operator) {
        List<StockLot> lots = lotRepository.findByBatchId(batch.getId());
        BigDecimal returned = BigDecimal.ZERO;
        for (StockLot lot : lots) {
            if (!"IN".equals(lot.getStatus()) && !"PENDING".equals(lot.getStatus())) {
                continue;
            }
            BigDecimal w = lot.getCurrentWeight();
            if (w.signum() <= 0) {
                continue;
            }
            if ("IN".equals(lot.getStatus()) && lot.getLocationId() != null) {
                Location loc = locationRepository.findById(lot.getLocationId()).orElse(null);
                Zone zone = lot.getZoneId() == null ? null
                        : zoneRepository.findById(lot.getZoneId()).orElse(null);
                if (loc != null) {
                    loc.setOccupiedKg(loc.getOccupiedKg().subtract(w).max(BigDecimal.ZERO));
                    locationRepository.save(loc);
                }
                if (zone != null) {
                    zone.setOccupiedKg(zone.getOccupiedKg().subtract(w).max(BigDecimal.ZERO));
                    zoneRepository.save(zone);
                }
            }
            StockMove m = new StockMove();
            m.setBatchId(batch.getId());
            m.setLotId(lot.getId());
            m.setReason(MoveReason.RETURN);
            m.setWeight(w);
            m.setOperator(operator);
            m.setMovedAt(LocalDateTime.now());
            m.setRemark(remark);
            moveRepository.save(m);
            lot.setCurrentWeight(BigDecimal.ZERO);
            lot.setStatus("OUT");
            lotRepository.save(lot);
            returned = returned.add(w);
        }
        if (lots.isEmpty()) {
            // 尚未质检生成货位批次：按净重退货
            returned = batch.getNetWeight().subtract(batch.getReturnedWeight());
            if (returned.signum() > 0) {
                StockMove m = new StockMove();
                m.setBatchId(batch.getId());
                m.setReason(MoveReason.RETURN);
                m.setWeight(returned);
                m.setOperator(operator);
                m.setMovedAt(LocalDateTime.now());
                m.setRemark(remark);
                moveRepository.save(m);
            }
        }
        batch.setReturnedWeight(batch.getReturnedWeight().add(returned.max(BigDecimal.ZERO)));
    }

    /** 批次剩余可处置重量：在库+待上架货位合计；无货位批次时按净重-已退。 */
    private BigDecimal remainingWeight(Batch batch) {
        List<StockLot> lots = lotRepository.findByBatchId(batch.getId());
        BigDecimal sum = BigDecimal.ZERO;
        for (StockLot lot : lots) {
            if ("IN".equals(lot.getStatus()) || "PENDING".equals(lot.getStatus())) {
                sum = sum.add(lot.getCurrentWeight());
            }
        }
        if (lots.isEmpty()) {
            sum = batch.getNetWeight().subtract(batch.getReturnedWeight());
        }
        return sum.max(BigDecimal.ZERO);
    }

    @Transactional(readOnly = true)
    public List<TempExceedCase> list(Long batchId, String status) {
        if (batchId != null) {
            return caseRepository.findByBatchIdOrderByCreatedAtDesc(batchId);
        }
        if (status != null && !status.isBlank()) {
            return caseRepository.findByStatusOrderByCreatedAtDesc(TempCaseStatus.valueOf(status));
        }
        return caseRepository.findAllByOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public TempExceedCase get(Long id) {
        return mustGet(id);
    }

    private TempExceedCase mustGet(Long id) {
        return caseRepository.findById(id).orElseThrow(() -> new BizException("处置单不存在: " + id));
    }
}
