package com.county.coldchain.service;

import com.county.coldchain.domain.entity.Alert;
import com.county.coldchain.domain.entity.Batch;
import com.county.coldchain.domain.entity.Bill;
import com.county.coldchain.domain.entity.Category;
import com.county.coldchain.domain.entity.Complaint;
import com.county.coldchain.domain.entity.Customer;
import com.county.coldchain.domain.entity.Inspection;
import com.county.coldchain.domain.entity.OutboundOrder;
import com.county.coldchain.domain.entity.Owner;
import com.county.coldchain.domain.entity.SortItem;
import com.county.coldchain.domain.entity.SortingRecord;
import com.county.coldchain.domain.entity.StockLot;
import com.county.coldchain.domain.entity.StockMove;
import com.county.coldchain.domain.entity.TempExceedCase;
import com.county.coldchain.domain.entity.TraceEvent;
import com.county.coldchain.repo.AlertRepository;
import com.county.coldchain.repo.BatchRepository;
import com.county.coldchain.repo.BillRepository;
import com.county.coldchain.repo.CategoryRepository;
import com.county.coldchain.repo.ComplaintRepository;
import com.county.coldchain.repo.CustomerRepository;
import com.county.coldchain.repo.InspectionRepository;
import com.county.coldchain.repo.OutboundOrderRepository;
import com.county.coldchain.repo.OwnerRepository;
import com.county.coldchain.repo.SortItemRepository;
import com.county.coldchain.repo.SortingRecordRepository;
import com.county.coldchain.repo.StockLotRepository;
import com.county.coldchain.repo.StockMoveRepository;
import com.county.coldchain.repo.TempExceedCaseRepository;
import com.county.coldchain.repo.TraceEventRepository;
import com.county.coldchain.support.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 监管追溯：某批货从入库到最终客户的完整流向（批次链一张图）。 */
@Service
@RequiredArgsConstructor
public class TraceQueryService {

    private final BatchRepository batchRepository;
    private final OwnerRepository ownerRepository;
    private final CustomerRepository customerRepository;
    private final CategoryRepository categoryRepository;
    private final TraceEventRepository traceEventRepository;
    private final InspectionRepository inspectionRepository;
    private final SortingRecordRepository sortingRepository;
    private final SortItemRepository sortItemRepository;
    private final StockLotRepository lotRepository;
    private final StockMoveRepository moveRepository;
    private final OutboundOrderRepository outboundRepository;
    private final ComplaintRepository complaintRepository;
    private final AlertRepository alertRepository;
    private final BillRepository billRepository;
    private final TempExceedCaseRepository tempCaseRepository;

    @Transactional(readOnly = true)
    public Map<String, Object> chain(Long batchId) {
        Batch b = batchRepository.findById(batchId).orElseThrow(() -> new BizException("批次不存在"));
        Owner owner = ownerRepository.findById(b.getOwnerId()).orElse(null);
        Category cat = categoryRepository.findById(b.getCategoryId()).orElse(null);
        Customer planned = b.getPlannedCustomerId() == null ? null
                : customerRepository.findById(b.getPlannedCustomerId()).orElse(null);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("batch", batchView(b, owner, cat, planned));

        List<TraceEvent> events = traceEventRepository.findByBatchIdOrderByEventAtAsc(batchId);
        m.put("timeline", events);

        List<Inspection> inspections = inspectionRepository.findByBatchIdOrderByInspectedAtDesc(batchId);
        m.put("inspections", inspections);

        List<TempExceedCase> tempCases = tempCaseRepository.findByBatchIdOrderByCreatedAtDesc(batchId);
        m.put("tempCases", tempCases);

        List<Map<String, Object>> sortings = new java.util.ArrayList<>();
        for (SortingRecord r : sortingRepository.findByBatchIdOrderBySortedAtAsc(batchId)) {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("record", r);
            sm.put("items", sortItemRepository.findBySortingId(r.getId()));
            sortings.add(sm);
        }
        m.put("sortings", sortings);

        List<StockLot> lots = lotRepository.findByBatchId(batchId);
        m.put("stockLots", lots);
        List<StockMove> moves = new java.util.ArrayList<>();
        for (StockLot l : lots) {
            moves.addAll(moveRepository.findByLotIdOrderByMovedAtAsc(l.getId()));
        }
        moves.sort(java.util.Comparator.comparing(StockMove::getMovedAt));
        m.put("moves", moves);

        List<OutboundOrder> orders = outboundRepository.findByBatchIdOrderByCreatedAtDesc(batchId);
        m.put("outbounds", orders);

        List<Complaint> complaints = complaintRepository.findByBatchIdOrderByCreatedAtDesc(batchId);
        m.put("complaints", complaints);

        List<Alert> alerts = alertRepository.findByBatchIdOrderByCreatedAtDesc(batchId);
        m.put("alerts", alerts);

        List<Bill> bills = billRepository.findByBatchIdOrderByCreatedAtDesc(batchId);
        m.put("bills", bills);

        // 最终客户流向汇总
        List<Map<String, Object>> flow = new java.util.ArrayList<>();
        for (OutboundOrder o : orders) {
            Customer c = customerRepository.findById(o.getCustomerId()).orElse(null);
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("orderNo", o.getOrderNo());
            f.put("customer", c == null ? "" : c.getName());
            f.put("customerChanged", o.getCustomerChanged());
            f.put("weight", o.getTotalWeight());
            f.put("amount", o.getTotalAmount());
            f.put("status", o.getStatus().name());
            f.put("outAt", o.getOutAt());
            f.put("complaints", complaints.stream()
                    .filter(x -> x.getOutboundId().equals(o.getId())).toList());
            flow.add(f);
        }
        m.put("finalFlow", flow);
        return m;
    }

    private Map<String, Object> batchView(Batch b, Owner owner, Category cat, Customer planned) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", b.getId());
        m.put("batchNo", b.getBatchNo());
        m.put("owner", owner == null ? "" : owner.getName());
        m.put("category", cat == null ? "" : cat.getName());
        m.put("origin", b.getOrigin());
        m.put("netWeight", b.getNetWeight());
        m.put("arrivalTemp", b.getArrivalTemp());
        m.put("packaging", b.getPackaging());
        m.put("vehicleNo", b.getVehicleNo());
        m.put("mixedLoad", b.getMixedLoad());
        m.put("plannedCustomer", planned == null ? "" : planned.getName());
        m.put("settleMethod", b.getSettleMethod().name());
        m.put("lockedPrice", b.getLockedPrice());
        m.put("settleByOriginalWeight", b.getSettleByOriginalWeight());
        m.put("status", b.getStatus().name());
        m.put("saleFrozen", b.getSaleFrozen());
        m.put("acceptedWeight", b.getAcceptedWeight());
        m.put("downgradeWeight", b.getDowngradeWeight());
        m.put("returnedWeight", b.getReturnedWeight());
        m.put("lossWeight", b.getLossWeight());
        m.put("outWeight", b.getOutWeight());
        m.put("arrivedAt", b.getArrivedAt());
        m.put("quarantine", b.getQuarantine());
        return m;
    }
}
