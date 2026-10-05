package com.county.coldchain.service;

import com.county.coldchain.domain.DomainEnums.BillItemType;
import com.county.coldchain.domain.DomainEnums.BillStatus;
import com.county.coldchain.domain.DomainEnums.MoveReason;
import com.county.coldchain.domain.DomainEnums.Responsibility;
import com.county.coldchain.domain.DomainEnums.Stage;
import com.county.coldchain.domain.entity.AdvanceLedger;
import com.county.coldchain.domain.entity.Batch;
import com.county.coldchain.domain.entity.Bill;
import com.county.coldchain.domain.entity.BillItem;
import com.county.coldchain.domain.entity.Category;
import com.county.coldchain.domain.entity.Complaint;
import com.county.coldchain.domain.entity.OutboundItem;
import com.county.coldchain.domain.entity.OutboundOrder;
import com.county.coldchain.domain.entity.Owner;
import com.county.coldchain.domain.entity.SortItem;
import com.county.coldchain.domain.entity.SortingRecord;
import com.county.coldchain.domain.entity.StockLot;
import com.county.coldchain.domain.entity.StockMove;
import com.county.coldchain.domain.entity.TempExceedCase;
import com.county.coldchain.repo.AdvanceLedgerRepository;
import com.county.coldchain.repo.BatchRepository;
import com.county.coldchain.repo.BillItemRepository;
import com.county.coldchain.repo.BillRepository;
import com.county.coldchain.repo.CategoryRepository;
import com.county.coldchain.repo.ComplaintRepository;
import com.county.coldchain.repo.OutboundItemRepository;
import com.county.coldchain.repo.OutboundOrderRepository;
import com.county.coldchain.repo.OwnerRepository;
import com.county.coldchain.repo.SortItemRepository;
import com.county.coldchain.repo.SortingRecordRepository;
import com.county.coldchain.repo.StockLotRepository;
import com.county.coldchain.repo.StockMoveRepository;
import com.county.coldchain.repo.TempExceedCaseRepository;
import com.county.coldchain.support.BizException;
import com.county.coldchain.web.dto.BillGenerateRequest;
import com.county.coldchain.web.dto.BillSettleRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 财务结算：按实际可售重量、等级、仓储费、分拣费、损耗与退货生成账单。
 * 一次降级、转仓、报损或投诉裁定都会改变本账单口径；货主预付款在结算时抵扣。
 */
@Service
@RequiredArgsConstructor
public class BillingService {

    private final BillRepository billRepository;
    private final BillItemRepository billItemRepository;
    private final BatchRepository batchRepository;
    private final CategoryRepository categoryRepository;
    private final OwnerRepository ownerRepository;
    private final StockLotRepository lotRepository;
    private final StockMoveRepository moveRepository;
    private final SortingRecordRepository sortingRepository;
    private final SortItemRepository sortItemRepository;
    private final OutboundOrderRepository outboundRepository;
    private final OutboundItemRepository outboundItemRepository;
    private final ComplaintRepository complaintRepository;
    private final AdvanceLedgerRepository advanceRepository;
    private final TempExceedCaseRepository tempCaseRepository;
    private final TraceService traceService;

    @Value("${app.billing.labor-rate-per-minute:0.6}")
    private BigDecimal laborRate;

    @Transactional
    public Bill generate(BillGenerateRequest req) {
        Batch batch = batchRepository.findById(req.getBatchId())
                .orElseThrow(() -> new BizException("批次不存在"));
        if (batch.getStatus() == com.county.coldchain.domain.DomainEnums.BatchStatus.RETURNED) {
            throw new BizException("整批退货批次不产生账单");
        }
        billRepository.findFirstByBatchIdAndStatus(batch.getId(), BillStatus.DRAFT).ifPresent(old -> {
            billItemRepository.findByBillId(old.getId()).forEach(billItemRepository::delete);
            billRepository.delete(old);
        });
        Bill bill = new Bill();
        bill.setBillNo("F" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + String.format("%03d", billRepository.count() + 1));
        bill.setBatchId(batch.getId());
        bill.setOwnerId(batch.getOwnerId());
        bill.setCutoffAt(LocalDateTime.now());
        bill.setStatus(BillStatus.DRAFT);
        bill.setRemark(req.getRemark());
        bill = billRepository.save(bill);

        Category cat = categoryRepository.findById(batch.getCategoryId()).orElseThrow();
        List<StockLot> lots = lotRepository.findByBatchId(batch.getId());
        LocalDateTime cutoff = bill.getCutoffAt();

        // 1) 销售收入（按实际出库、按等级价/锁价）
        BigDecimal grossValue = BigDecimal.ZERO;
        Map<Long, BigDecimal> lotPrice = new HashMap<>();
        for (StockLot lot : lots) {
            if (lot.getUnitPrice() != null) {
                lotPrice.put(lot.getId(), lot.getUnitPrice());
            }
        }
        List<OutboundOrder> orders = outboundRepository.findByBatchIdOrderByCreatedAtDesc(batch.getId());
        BigDecimal saleAmount = BigDecimal.ZERO;
        for (OutboundOrder o : orders) {
            for (OutboundItem it : outboundItemRepository.findByOutboundId(o.getId())) {
                addItem(bill, BillItemType.SALE,
                        "出库销售 " + o.getOrderNo() + " " + it.getGrade(),
                        it.getWeight(), it.getUnitPrice(), it.getAmount(), null);
                saleAmount = saleAmount.add(it.getAmount());
            }
        }
        grossValue = grossValue.add(saleAmount);

        // 2) 期末在库货值（信息项，不进应付）
        BigDecimal remainValue = BigDecimal.ZERO;
        BigDecimal remainWeight = BigDecimal.ZERO;
        for (StockLot lot : lots) {
            if ("IN".equals(lot.getStatus()) && lot.getCurrentWeight().signum() > 0) {
                BigDecimal v = lot.getCurrentWeight()
                        .multiply(lot.getUnitPrice() == null ? cat.getDefaultPriceB() : lot.getUnitPrice());
                remainValue = remainValue.add(v);
                remainWeight = remainWeight.add(lot.getCurrentWeight());
            }
        }
        if (remainValue.signum() > 0) {
            addItem(bill, BillItemType.REMAIN_VALUE, "期末在库货值（不参与本次付款）",
                    remainWeight, null, BigDecimal.ZERO, null);
        }

        // 3) 仓储费：按货位重量·天（出库/报损前在仓天数照计）
        BigDecimal storageFee = BigDecimal.ZERO;
        for (StockLot lot : lots) {
            BigDecimal weightDays = weightDays(lot, cutoff);
            if (weightDays.signum() <= 0) {
                continue;
            }
            BigDecimal fee = weightDays.multiply(cat.getStorageFeeRate())
                    .setScale(2, RoundingMode.HALF_UP).negate();
            int days = daysBetween(lot.getInAt(), cutoff);
            addItem(bill, BillItemType.STORAGE_FEE,
                    "仓储费 " + lot.getLotNo() + " " + lot.getGrade().desc + "（"
                            + weightDays + " kg·天 × " + cat.getStorageFeeRate() + "）",
                    null, cat.getStorageFeeRate(), fee, days);
            storageFee = storageFee.add(fee);
        }

        // 3.5) 预冷费：温度超标选择预冷复检的批次，按预冷重量一次性计收（复检合格与否均计）
        BigDecimal precoolTotal = BigDecimal.ZERO;
        for (TempExceedCase tc : tempCaseRepository.findByBatchIdOrderByCreatedAtDesc(batch.getId())) {
            if (tc.getPrecoolFee() != null && tc.getPrecoolFee().signum() > 0) {
                BigDecimal fee = tc.getPrecoolFee().negate();
                addItem(bill, BillItemType.PRECOOL_FEE,
                        "预冷费 " + tc.getCaseNo() + "（温度超标预冷 " + tc.getPrecoolWeight() + "kg）",
                        tc.getPrecoolWeight(), null, fee, null);
                precoolTotal = precoolTotal.add(fee);
            }
        }

        // 4) 分拣服务费 + 工时费
        BigDecimal sortFeeTotal = BigDecimal.ZERO;
        BigDecimal laborTotal = BigDecimal.ZERO;
        for (SortingRecord rec : sortingRepository.findByBatchIdOrderBySortedAtAsc(batch.getId())) {
            BigDecimal sortFee = rec.getOutputWeight().multiply(cat.getSortFeeRate())
                    .setScale(2, RoundingMode.HALF_UP).negate();
            addItem(bill, BillItemType.SORT_FEE,
                    "分拣服务费 " + rec.getSortingNo() + "（" + rec.getOutputWeight() + "kg × "
                            + cat.getSortFeeRate() + "）",
                    rec.getOutputWeight(), cat.getSortFeeRate(), sortFee, null);
            sortFeeTotal = sortFeeTotal.add(sortFee);

            BigDecimal laborFee = BigDecimal.valueOf(rec.getLaborMinutes())
                    .multiply(laborRate).setScale(2, RoundingMode.HALF_UP).negate();
            if (laborFee.signum() < 0) {
                addItem(bill, BillItemType.LABOR_FEE,
                        "低温仓分拣工时 " + rec.getSortingNo() + "（" + rec.getLaborMinutes()
                                + " 分钟 × " + laborRate + " 元/分钟）",
                        null, laborRate, laborFee, null);
                laborTotal = laborTotal.add(laborFee);
            }
        }

        // 5) 损耗责任
        BigDecimal lossComp = BigDecimal.ZERO;
        BigDecimal lossDeduct = BigDecimal.ZERO;
        BigDecimal avg = avgAcceptedPrice(lots, cat);
        if (Boolean.TRUE.equals(batch.getSettleByOriginalWeight())) {
            // 货主要求按原重结算：除退货外的全部损耗由冷库按均价补偿
            BigDecimal compWeight = batch.getNetWeight()
                    .subtract(nz(batch.getReturnedWeight()))
                    .subtract(nz(batch.getOutWeight()))
                    .subtract(remainWeight);
            if (compWeight.signum() > 0) {
                BigDecimal comp = compWeight.multiply(avg).setScale(2, RoundingMode.HALF_UP);
                addItem(bill, BillItemType.LOSS_DEDUCT,
                        "按原重结算·冷库承担损耗补偿（" + compWeight + "kg × " + avg + " 元/kg）",
                        compWeight, avg, comp, null);
                lossComp = lossComp.add(comp);
            }
        } else {
            // 分拣损耗争议中冷库承担的差额，按分拣产出均价补偿货主
            for (SortingRecord rec : sortingRepository.findByBatchIdOrderBySortedAtAsc(batch.getId())) {
                if (Boolean.TRUE.equals(rec.getDisputed()) && Boolean.TRUE.equals(rec.getDisputeResolved())
                        && rec.getAdjudicatedLossWeight() != null) {
                    BigDecimal warehouseBorne = rec.getLossWeight().subtract(rec.getAdjudicatedLossWeight());
                    if (warehouseBorne.signum() > 0) {
                        BigDecimal comp = warehouseBorne.multiply(avg).setScale(2, RoundingMode.HALF_UP);
                        addItem(bill, BillItemType.LOSS_DEDUCT,
                                "分拣争议·冷库承担损耗补偿（" + warehouseBorne + "kg × " + avg + " 元/kg）",
                                warehouseBorne, avg, comp, null);
                        lossComp = lossComp.add(comp);
                    }
                }
            }
            // 冷库责任报损（库内报损）按货位价补偿货主
            for (StockLot lot : lots) {
                if (lot.getLossWeight().signum() > 0) {
                    List<StockMove> lossMoves = moveRepository.findByLotIdOrderByMovedAtAsc(lot.getId()).stream()
                            .filter(m -> m.getReason() == MoveReason.LOSS).toList();
                    BigDecimal warehouseLoss = lossMoves.stream()
                            .filter(m -> m.getRemark() != null && m.getRemark().contains("冷库责任"))
                            .map(StockMove::getWeight).reduce(BigDecimal.ZERO, BigDecimal::add);
                    if (warehouseLoss.signum() > 0) {
                        BigDecimal price = lot.getUnitPrice() == null ? cat.getDefaultPriceB() : lot.getUnitPrice();
                        BigDecimal comp = warehouseLoss.multiply(price).setScale(2, RoundingMode.HALF_UP);
                        addItem(bill, BillItemType.LOSS_DEDUCT,
                                "冷库责任报损补偿 " + lot.getLotNo() + "（" + warehouseLoss + "kg × "
                                        + price + "）", warehouseLoss, price, comp, null);
                        lossComp = lossComp.add(comp);
                    }
                    BigDecimal ownerLoss = lot.getLossWeight().subtract(warehouseLoss);
                    if (ownerLoss.signum() > 0) {
                        addItem(bill, BillItemType.LOSS_DEDUCT,
                                "货主承担损耗（不计价）" + lot.getLotNo(), ownerLoss, null,
                                BigDecimal.ZERO, null);
                    }
                }
            }
        }

        // 6) 投诉赔付：货主责任从货款扣；冷库责任由冷库直接对客赔付（不入货主账单，仅备注）
        BigDecimal complaintDeduct = BigDecimal.ZERO;
        for (Complaint c : complaintRepository.findByBatchIdOrderByCreatedAtDesc(batch.getId())) {
            if (!c.getResolved()) {
                continue;
            }
            if (c.getResponsibility() == Responsibility.OWNER) {
                BigDecimal d = c.getSettledAmount().negate();
                addItem(bill, BillItemType.COMPLAINT_DEDUCT,
                        "投诉 " + c.getComplaintNo() + " 货主责任赔付", null, null, d, null);
                complaintDeduct = complaintDeduct.add(d);
            } else {
                addItem(bill, BillItemType.COMPLAINT_DEDUCT,
                        "投诉 " + c.getComplaintNo() + " 责任方：" + c.getResponsibility().desc
                                + "（冷库对客户赔付，不扣货主）", null, null, BigDecimal.ZERO, null);
            }
        }

        // 7) 退货重量信息
        if (nz(batch.getReturnedWeight()).signum() > 0) {
            addItem(bill, BillItemType.RETURN_DEDUCT,
                    "质检/退货重量（不计仓储分拣费）", batch.getReturnedWeight(), null, BigDecimal.ZERO, null);
        }

        // 8) 预付款抵扣（草稿先按可用余额预占，确认时可改）
        Owner owner = ownerRepository.findById(batch.getOwnerId()).orElseThrow();
        BigDecimal availableAdvance = owner.getAdvanceBalance() == null ? BigDecimal.ZERO : owner.getAdvanceBalance();
        BigDecimal deduct = req.getAdvanceDeduct() == null ? availableAdvance : req.getAdvanceDeduct();
        deduct = deduct.min(availableAdvance).max(BigDecimal.ZERO);
        if (deduct.signum() > 0) {
            addItem(bill, BillItemType.ADVANCE_DEDUCT, "预付款抵扣", null, null, deduct.negate(), null);
        }
        bill.setAdvanceDeducted(deduct);

        grossValue = grossValue.add(lossComp);
        BigDecimal deduction = storageFee.add(precoolTotal).add(sortFeeTotal).add(laborTotal)
                .add(lossDeduct).add(complaintDeduct).subtract(deduct);
        bill.setGrossValue(grossValue);
        bill.setTotalDeduction(deduction);
        bill.setNetPayable(grossValue.add(deduction).setScale(2, RoundingMode.HALF_UP));
        billRepository.save(bill);

        traceService.log(batch.getId(), null, null, Stage.SETTLEMENT,
                "生成账单草稿 " + bill.getBillNo(),
                String.format("销售收入 %s 元，仓储/分拣/工时等扣款合计 %s 元，预付款抵扣 %s 元，应付货主 %s 元",
                        saleAmount, storageFee.add(sortFeeTotal).add(laborTotal).add(complaintDeduct),
                        deduct, bill.getNetPayable()),
                "财务");
        return bill;
    }

    @Transactional
    public Bill confirm(Long billId) {
        Bill bill = mustGet(billId);
        if (bill.getStatus() != BillStatus.DRAFT) {
            throw new BizException("只有草稿账单可确认");
        }
        bill.setStatus(BillStatus.CONFIRMED);
        bill.setConfirmedAt(LocalDateTime.now());
        billRepository.save(bill);
        traceService.log(bill.getBatchId(), null, null, Stage.SETTLEMENT,
                "账单确认 " + bill.getBillNo(), "应付货主 " + bill.getNetPayable() + " 元，待支付。", "财务");
        return bill;
    }

    @Transactional
    public Bill settle(Long billId, BillSettleRequest req) {
        Bill bill = mustGet(billId);
        if (bill.getStatus() == BillStatus.PAID) {
            throw new BizException("账单已结算");
        }
        BigDecimal cash = req.getPaidAmount() == null
                ? bill.getNetPayable() : req.getPaidAmount();
        if (cash.signum() < 0) {
            throw new BizException("实付金额不能为负");
        }
        bill.setPaidAmount(cash);
        bill.setStatus(BillStatus.PAID);
        bill.setPaidAt(LocalDateTime.now());
        if (req.getRemark() != null) {
            bill.setRemark(req.getRemark());
        }
        billRepository.save(bill);

        // 预付款抵扣落账（REPAY 回收预付款）
        if (bill.getAdvanceDeducted().signum() > 0) {
            Owner owner = ownerRepository.findById(bill.getOwnerId()).orElseThrow();
            AdvanceLedger l = new AdvanceLedger();
            l.setOwnerId(owner.getId());
            l.setBatchId(bill.getBatchId());
            l.setDirection("REPAY");
            l.setAmount(bill.getAdvanceDeducted());
            l.setRemark("账单 " + bill.getBillNo() + " 结算抵扣预付款");
            l.setOccurredAt(LocalDateTime.now());
            advanceRepository.save(l);
            owner.setAdvanceBalance(owner.getAdvanceBalance().subtract(bill.getAdvanceDeducted()));
            ownerRepository.save(owner);
        }
        traceService.log(bill.getBatchId(), null, null, Stage.SETTLEMENT,
                "账单结算完成 " + bill.getBillNo(),
                "现金/转账支付 " + cash + " 元，预付款抵扣 " + bill.getAdvanceDeducted()
                        + " 元，批次链财务闭环。", "财务");
        return bill;
    }

    @Transactional(readOnly = true)
    public List<Bill> list(Long ownerId) {
        return ownerId == null ? billRepository.findAll()
                : billRepository.findByOwnerIdOrderByCreatedAtDesc(ownerId);
    }

    @Transactional(readOnly = true)
    public Bill detail(Long id) {
        return mustGet(id);
    }

    @Transactional(readOnly = true)
    public List<BillItem> items(Long billId) {
        return billItemRepository.findByBillId(billId);
    }

    private Bill mustGet(Long id) {
        return billRepository.findById(id).orElseThrow(() -> new BizException("账单不存在: " + id));
    }

    private void addItem(Bill bill, BillItemType type, String summary, BigDecimal weight,
                         BigDecimal unitPrice, BigDecimal amount, Integer days) {
        BillItem it = new BillItem();
        it.setBillId(bill.getId());
        it.setType(type);
        it.setSummary(summary);
        it.setWeight(weight);
        it.setUnitPrice(unitPrice);
        it.setAmount(amount == null ? BigDecimal.ZERO : amount.setScale(2, RoundingMode.HALF_UP));
        it.setDays(days);
        billItemRepository.save(it);
    }

    /** 重量·天：以入库时刻为起点，出库/报损事件截断，剩余算到账单截止时刻。 */
    private BigDecimal weightDays(StockLot lot, LocalDateTime cutoff) {
        LocalDateTime start = lot.getInAt();
        BigDecimal wd = BigDecimal.ZERO;
        BigDecimal remaining = lot.getInitialWeight();
        List<StockMove> events = moveRepository.findByLotIdOrderByMovedAtAsc(lot.getId()).stream()
                .filter(m -> m.getReason() == MoveReason.OUTBOUND || m.getReason() == MoveReason.LOSS)
                .sorted((a, b) -> a.getMovedAt().compareTo(b.getMovedAt()))
                .toList();
        for (StockMove m : events) {
            int days = Math.max(1, daysBetween(start, m.getMovedAt()));
            wd = wd.add(m.getWeight().multiply(BigDecimal.valueOf(days)));
            remaining = remaining.subtract(m.getWeight());
        }
        if (remaining.signum() > 0) {
            int days = Math.max(1, daysBetween(start, cutoff));
            wd = wd.add(remaining.multiply(BigDecimal.valueOf(days)));
        }
        return wd.setScale(2, RoundingMode.HALF_UP);
    }

    private int daysBetween(LocalDateTime a, LocalDateTime b) {
        return (int) Math.max(0, Duration.between(a, b).toDays());
    }

    private BigDecimal avgAcceptedPrice(List<StockLot> lots, Category cat) {
        BigDecimal w = BigDecimal.ZERO;
        BigDecimal v = BigDecimal.ZERO;
        for (StockLot lot : lots) {
            BigDecimal p = lot.getUnitPrice() == null ? cat.getDefaultPriceB() : lot.getUnitPrice();
            w = w.add(lot.getInitialWeight());
            v = v.add(lot.getInitialWeight().multiply(p));
        }
        if (w.signum() == 0) {
            return cat.getDefaultPriceB();
        }
        return v.divide(w, 2, RoundingMode.HALF_UP);
    }

    private BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}
