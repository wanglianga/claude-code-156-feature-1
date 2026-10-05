package com.county.coldchain.service;

import com.county.coldchain.domain.DomainEnums.AlertLevel;
import com.county.coldchain.domain.DomainEnums.AlertType;
import com.county.coldchain.domain.DomainEnums.BatchStatus;
import com.county.coldchain.domain.DomainEnums.LockStatus;
import com.county.coldchain.domain.DomainEnums.MoveReason;
import com.county.coldchain.domain.DomainEnums.OrderStatus;
import com.county.coldchain.domain.DomainEnums.Stage;
import com.county.coldchain.domain.entity.Batch;
import com.county.coldchain.domain.entity.Category;
import com.county.coldchain.domain.entity.Customer;
import com.county.coldchain.domain.entity.Location;
import com.county.coldchain.domain.entity.OutboundItem;
import com.county.coldchain.domain.entity.OutboundOrder;
import com.county.coldchain.domain.entity.PriceLock;
import com.county.coldchain.domain.entity.StockLot;
import com.county.coldchain.domain.entity.StockMove;
import com.county.coldchain.domain.entity.Zone;
import com.county.coldchain.repo.BatchRepository;
import com.county.coldchain.repo.CategoryRepository;
import com.county.coldchain.repo.CustomerRepository;
import com.county.coldchain.repo.LocationRepository;
import com.county.coldchain.repo.OutboundItemRepository;
import com.county.coldchain.repo.OutboundOrderRepository;
import com.county.coldchain.repo.PriceLockRepository;
import com.county.coldchain.repo.StockLotRepository;
import com.county.coldchain.repo.StockMoveRepository;
import com.county.coldchain.repo.ZoneRepository;
import com.county.coldchain.support.BizException;
import com.county.coldchain.web.dto.OutboundRequest;
import com.county.coldchain.web.dto.SubstituteRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;

/**
 * 出库调度：排单→执行出库。
 * 处理临时改客户、客户优先出库、锁价履约、温区故障紧急出货与批次可售重量扣减。
 */
@Service
@RequiredArgsConstructor
public class OutboundService {

    private final OutboundOrderRepository orderRepository;
    private final OutboundItemRepository itemRepository;
    private final StockLotRepository lotRepository;
    private final BatchRepository batchRepository;
    private final CustomerRepository customerRepository;
    private final CategoryRepository categoryRepository;
    private final PriceLockRepository priceLockRepository;
    private final LocationRepository locationRepository;
    private final ZoneRepository zoneRepository;
    private final StockMoveRepository moveRepository;
    private final AlertService alertService;
    private final TraceService traceService;

    /** 排单（PLANNED）：记录客户、优先级、临时改客户。 */
    @Transactional
    public OutboundOrder plan(OutboundRequest req) {
        Batch batch = batchRepository.findById(req.getBatchId())
                .orElseThrow(() -> new BizException("批次不存在"));
        Customer customer = customerRepository.findById(req.getCustomerId())
                .orElseThrow(() -> new BizException("客户不存在"));
        ensureSellable(batch);
        validateItems(req.getItems(), batch);

        OutboundOrder o = buildOrder(batch, customer, req.getItems(), req.getOperator(),
                Boolean.TRUE.equals(req.getPriority()), req.getRemark(), req.getOverrideUnitPrice());

        boolean changed = batch.getPlannedCustomerId() != null
                && !batch.getPlannedCustomerId().equals(customer.getId());
        if (changed) {
            o.setCustomerChanged(true);
            o.setOriginalCustomerId(batch.getPlannedCustomerId());
            Customer orig = customerRepository.findById(batch.getPlannedCustomerId()).orElse(null);
            alertService.raise(AlertType.CUSTOMER_CHANGED, AlertLevel.WARN, batch.getId(), null,
                    "临时改客户 " + batch.getBatchNo(),
                    "原计划客户 " + (orig == null ? batch.getPlannedCustomerId() : orig.getName())
                            + " 临时改为 " + customer.getName()
                            + "，原客户锁价/预付款关系需财务复核。", req.getOperator());
        }
        if (o.getPriority()) {
            alertService.raise(AlertType.PRIORITY_OUT, AlertLevel.WARN, batch.getId(), null,
                    "客户要求优先出库 " + batch.getBatchNo(),
                    customer.getName() + " 要求优先出库，调度应在排单序列中前置。", req.getOperator());
        }
        return orderRepository.save(o);
    }

    /** 温度超标预冷复检中的批次冻结对外销售：禁止排单与出库。 */
    private void ensureSellable(Batch batch) {
        if (Boolean.TRUE.equals(batch.getSaleFrozen())) {
            throw new BizException("批次温度超标预冷复检中，已冻结对外销售，复检合格前禁止排单/出库");
        }
    }

    /** 创建 PLANNED 出库单并落明细（价格：手工单价 > 客户锁价 > 货位批次价）。 */
    private OutboundOrder buildOrder(Batch batch, Customer customer, List<OutboundRequest.Item> items,
                                     String operator, boolean priority, String remark,
                                     BigDecimal overrideUnitPrice) {
        OutboundOrder o = new OutboundOrder();
        o.setOrderNo("O" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + String.format("%03d", orderRepository.count() + 1));
        o.setCustomerId(customer.getId());
        o.setBatchId(batch.getId());
        o.setPriority(priority);
        o.setOperator(operator);
        o.setPlannedAt(LocalDateTime.now());
        o.setRemark(remark);
        o = orderRepository.save(o);

        Category cat = categoryRepository.findById(batch.getCategoryId()).orElseThrow();
        for (OutboundRequest.Item it : items) {
            StockLot lot = lotRepository.findById(it.getLotId())
                    .orElseThrow(() -> new BizException("货位批次不存在"));
            BigDecimal price = resolvePrice(overrideUnitPrice, it, customer, cat);
            OutboundItem oi = new OutboundItem();
            oi.setOutboundId(o.getId());
            oi.setLotId(lot.getId());
            oi.setGrade(lot.getGrade().name());
            oi.setWeight(it.getWeight());
            oi.setUnitPrice(price);
            oi.setAmount(price.multiply(it.getWeight()).setScale(2, RoundingMode.HALF_UP));
            itemRepository.save(oi);
            o.setTotalWeight(o.getTotalWeight().add(it.getWeight()));
            o.setTotalAmount(o.getTotalAmount().add(oi.getAmount()));
        }
        // 锁价标记
        PriceLock lock = findActiveLock(customer.getId(), cat.getCode());
        if (lock != null) {
            o.setPriceLocked(true);
            o.setLockedPrice(lock.getLockedPrice());
        }
        if (overrideUnitPrice != null) {
            o.setLockedPrice(overrideUnitPrice);
        }
        return orderRepository.save(o);
    }

    /** 温度超标复检不合格/现场退货时，自动撤回批次全部待出库计划。 */
    @Transactional
    public int withdrawPlannedOrders(Long batchId, String reason, String operator) {
        List<OutboundOrder> planned = orderRepository.findByBatchIdOrderByCreatedAtDesc(batchId)
                .stream().filter(o -> o.getStatus() == OrderStatus.PLANNED).toList();
        for (OutboundOrder o : planned) {
            o.setStatus(OrderStatus.WITHDRAWN);
            o.setWithdrawReason(reason);
            orderRepository.save(o);
            Customer c = customerRepository.findById(o.getCustomerId()).orElse(null);
            String customerName = c == null ? String.valueOf(o.getCustomerId()) : c.getName();
            alertService.raise(AlertType.PLAN_WITHDRAWN, AlertLevel.CRITICAL, batchId, null,
                    "出库计划撤回 " + o.getOrderNo(),
                    "分配给 " + customerName + " 的出库计划 " + o.getOrderNo() + "（" + o.getTotalWeight()
                            + "kg）因「" + reason + "」自动撤回，销售员需重新选择替代批次并向客户说明到货差异。",
                    operator);
            traceService.log(batchId, null, o.getCustomerId(), Stage.OUTBOUND,
                    "出库计划撤回 " + o.getOrderNo(),
                    "原计划出库 " + o.getTotalWeight() + "kg 给客户 " + customerName
                            + "，原因：" + reason + "。待销售员选择替代批次重新排单。",
                    operator);
        }
        return planned.size();
    }

    /** 销售员为已撤回出库单选择替代批次重新排单，并向客户说明到货差异。 */
    @Transactional
    public OutboundOrder substitute(Long orderId, SubstituteRequest req) {
        OutboundOrder old = orderRepository.findById(orderId)
                .orElseThrow(() -> new BizException("原出库单不存在"));
        if (old.getStatus() != OrderStatus.WITHDRAWN) {
            throw new BizException("只有已撤回的出库单才能替代排单");
        }
        Batch batch = batchRepository.findById(req.getBatchId())
                .orElseThrow(() -> new BizException("替代批次不存在"));
        if (batch.getStatus() == BatchStatus.RETURNED) {
            throw new BizException("替代批次已退货关闭，不能用于替代排单");
        }
        ensureSellable(batch);
        Customer customer = customerRepository.findById(old.getCustomerId())
                .orElseThrow(() -> new BizException("客户不存在"));
        validateItems(req.getItems(), batch);

        String operator = req.getOperator() == null ? "销售员" : req.getOperator();
        OutboundOrder o = buildOrder(batch, customer, req.getItems(), operator,
                Boolean.TRUE.equals(req.getPriority()), req.getRemark(), null);
        o.setReplacesOrderId(old.getId());
        o.setDifferenceNote(req.getDifferenceNote());
        o = orderRepository.save(o);

        Batch oldBatch = batchRepository.findById(old.getBatchId()).orElse(null);
        traceService.log(batch.getId(), null, customer.getId(), Stage.OUTBOUND,
                "替代排单 " + o.getOrderNo(),
                "替代被撤回单 " + old.getOrderNo() + "（原批次 "
                        + (oldBatch == null ? old.getBatchId() : oldBatch.getBatchNo())
                        + " 温度超标处置撤回）。向客户说明到货差异：" + req.getDifferenceNote(),
                operator);
        if (oldBatch != null) {
            traceService.log(oldBatch.getId(), null, customer.getId(), Stage.OUTBOUND,
                    "撤回计划已由替代批次履约",
                    "原出库单 " + old.getOrderNo() + " 改由批次 " + batch.getBatchNo() + " 替代排单 "
                            + o.getOrderNo() + "，客户 " + customer.getName() + " 已告知到货差异。",
                    operator);
        }
        return o;
    }

    /** 执行出库：扣减重、释放库位温区、履约锁价、推进批次状态。 */
    @Transactional
    public OutboundOrder execute(Long orderId, String operator) {
        OutboundOrder o = orderRepository.findById(orderId)
                .orElseThrow(() -> new BizException("出库单不存在"));
        if (o.getStatus() != OrderStatus.PLANNED) {
            throw new BizException("出库单已执行");
        }
        Batch batch = batchRepository.findById(o.getBatchId()).orElseThrow();
        ensureSellable(batch);
        Customer customer = customerRepository.findById(o.getCustomerId()).orElseThrow();
        List<OutboundItem> items = itemRepository.findByOutboundId(o.getId());

        for (OutboundItem it : items) {
            StockLot lot = lotRepository.findById(it.getLotId()).orElseThrow();
            if (!"IN".equals(lot.getStatus())) {
                throw new BizException("货位批次 " + lot.getLotNo() + " 非在库状态，不能出库");
            }
            if (it.getWeight().compareTo(lot.getCurrentWeight()) > 0) {
                throw new BizException("货位批次 " + lot.getLotNo() + " 可售重量不足："
                        + lot.getCurrentWeight() + "kg");
            }
            Location loc = locationRepository.findById(lot.getLocationId()).orElse(null);
            Zone zone = loc == null ? null : zoneRepository.findById(loc.getZoneId()).orElse(null);
            if (loc != null && zone != null) {
                loc.setOccupiedKg(loc.getOccupiedKg().subtract(it.getWeight()).max(BigDecimal.ZERO));
                locationRepository.save(loc);
                zone.setOccupiedKg(zone.getOccupiedKg().subtract(it.getWeight()).max(BigDecimal.ZERO));
                zoneRepository.save(zone);
            }
            lot.setCurrentWeight(lot.getCurrentWeight().subtract(it.getWeight()));
            lot.setOutWeight(lot.getOutWeight().add(it.getWeight()));
            if (lot.getCurrentWeight().signum() == 0) {
                lot.setStatus("OUT");
            }
            lotRepository.save(lot);

            StockMove m = new StockMove();
            m.setBatchId(batch.getId());
            m.setLotId(lot.getId());
            m.setFromLocationId(lot.getLocationId());
            m.setFromZoneId(lot.getZoneId());
            m.setReason(MoveReason.OUTBOUND);
            m.setWeight(it.getWeight());
            m.setOperator(operator == null ? o.getOperator() : operator);
            m.setMovedAt(LocalDateTime.now());
            m.setRemark("销售出库 → " + customer.getName() + "，单号 " + o.getOrderNo()
                    + "，" + lot.getGrade().desc + " " + it.getWeight() + "kg@" + it.getUnitPrice());
            moveRepository.save(m);
        }

        batch.setOutWeight(batch.getOutWeight().add(o.getTotalWeight()));
        BigDecimal remainInStore = stockRemaining(batch.getId());
        if (remainInStore.signum() <= 0) {
            batch.setStatus(BatchStatus.OUT);
        } else {
            batch.setStatus(BatchStatus.PARTIAL_OUT);
        }
        batchRepository.save(batch);

        // 锁价履约
        Category cat = categoryRepository.findById(batch.getCategoryId()).orElseThrow();
        PriceLock lock = findActiveLock(customer.getId(), cat.getCode());
        if (lock != null) {
            lock.setFulfilledQty(lock.getFulfilledQty().add(o.getTotalWeight()));
            if (lock.getFulfilledQty().compareTo(lock.getLockedQty()) >= 0) {
                lock.setStatus(LockStatus.FULFILLED);
            }
            priceLockRepository.save(lock);
        }

        o.setStatus(OrderStatus.OUT);
        o.setOutAt(LocalDateTime.now());
        if (operator != null) {
            o.setOperator(operator);
        }
        orderRepository.save(o);

        traceService.log(batch.getId(), null, customer.getId(), Stage.OUTBOUND,
                "出库发运 " + o.getOrderNo() + " → " + customer.getName(),
                String.format("出库 %skg，金额 %s 元%s%s；批次在库剩余 %skg",
                        o.getTotalWeight(), o.getTotalAmount(),
                        Boolean.TRUE.equals(o.getCustomerChanged()) ? "（临时改客户）" : "",
                        Boolean.TRUE.equals(o.getPriceLocked()) ? "（锁价 " + o.getLockedPrice() + " 元/kg）" : "",
                        remainInStore),
                o.getOperator());
        return o;
    }

    @Transactional(readOnly = true)
    public List<OutboundOrder> list(String status) {
        if (status == null || status.isBlank()) {
            return orderRepository.findAll();
        }
        return orderRepository.findByStatus(OrderStatus.valueOf(status));
    }

    @Transactional(readOnly = true)
    public List<OutboundItem> items(Long orderId) {
        return itemRepository.findByOutboundId(orderId);
    }

    private void validateItems(List<OutboundRequest.Item> items, Batch batch) {
        if (items == null || items.isEmpty()) {
            throw new BizException("出库明细不能为空");
        }
        for (OutboundRequest.Item it : items) {
            StockLot lot = lotRepository.findById(it.getLotId())
                    .orElseThrow(() -> new BizException("货位批次不存在"));
            if (!lot.getBatchId().equals(batch.getId())) {
                throw new BizException("货位批次 " + lot.getLotNo() + " 不属于该批次");
            }
            if (it.getWeight().signum() <= 0) {
                throw new BizException("出库重量必须大于 0");
            }
        }
    }

    /** 价格优先级：手工指定 > 客户锁价 > 货位批次结算价。 */
    private BigDecimal resolvePrice(BigDecimal overrideUnitPrice, OutboundRequest.Item item,
                                    Customer customer, Category cat) {
        if (item.getUnitPrice() != null) {
            return item.getUnitPrice();
        }
        if (overrideUnitPrice != null) {
            return overrideUnitPrice;
        }
        PriceLock lock = findActiveLock(customer.getId(), cat.getCode());
        if (lock != null) {
            return lock.getLockedPrice();
        }
        StockLot lot = lotRepository.findById(item.getLotId()).orElseThrow();
        return lot.getUnitPrice() == null ? cat.getDefaultPriceB() : lot.getUnitPrice();
    }

    private PriceLock findActiveLock(Long customerId, String categoryCode) {
        LocalDate today = LocalDate.now();
        return priceLockRepository.findByCustomerIdAndStatus(customerId, LockStatus.ACTIVE).stream()
                .filter(l -> l.getCategoryCode().equals(categoryCode))
                .filter(l -> l.getValidTo() == null || !l.getValidTo().isBefore(today))
                .min(Comparator.comparing(l -> l.getValidFrom() == null ? today : l.getValidFrom()))
                .orElse(null);
    }

    private BigDecimal stockRemaining(Long batchId) {
        return lotRepository.findByBatchId(batchId).stream()
                .filter(l -> "IN".equals(l.getStatus()))
                .map(StockLot::getCurrentWeight)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
