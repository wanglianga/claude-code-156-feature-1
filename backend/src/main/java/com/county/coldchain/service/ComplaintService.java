package com.county.coldchain.service;

import com.county.coldchain.domain.DomainEnums.AlertLevel;
import com.county.coldchain.domain.DomainEnums.AlertType;
import com.county.coldchain.domain.DomainEnums.ComplaintType;
import com.county.coldchain.domain.DomainEnums.RecheckResult;
import com.county.coldchain.domain.DomainEnums.Responsibility;
import com.county.coldchain.domain.DomainEnums.Stage;
import com.county.coldchain.domain.DomainEnums.TempDecision;
import com.county.coldchain.domain.entity.Batch;
import com.county.coldchain.domain.entity.Category;
import com.county.coldchain.domain.entity.Complaint;
import com.county.coldchain.domain.entity.OutboundOrder;
import com.county.coldchain.domain.entity.TempExceptionRecord;
import com.county.coldchain.repo.AlertRepository;
import com.county.coldchain.repo.BatchRepository;
import com.county.coldchain.repo.CategoryRepository;
import com.county.coldchain.repo.ComplaintRepository;
import com.county.coldchain.repo.OutboundOrderRepository;
import com.county.coldchain.repo.TempExceptionRepository;
import com.county.coldchain.support.BizException;
import com.county.coldchain.web.dto.ComplaintRequest;
import com.county.coldchain.web.dto.ComplaintResolveRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 出库后客户投诉：坏果等问题回挂批次链，裁定赔付进入货主账单扣款。 */
@Service
@RequiredArgsConstructor
public class ComplaintService {

    private final ComplaintRepository complaintRepository;
    private final OutboundOrderRepository outboundRepository;
    private final BatchRepository batchRepository;
    private final CategoryRepository categoryRepository;
    private final TempExceptionRepository tempExceptionRepository;
    private final AlertRepository alertRepository;
    private final AlertService alertService;
    private final TraceService traceService;

    @Transactional
    public Complaint create(ComplaintRequest req) {
        OutboundOrder o = outboundRepository.findById(req.getOutboundId())
                .orElseThrow(() -> new BizException("出库单不存在"));
        Complaint c = new Complaint();
        c.setComplaintNo("C" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + String.format("%03d", complaintRepository.count() + 1));
        c.setOutboundId(o.getId());
        c.setBatchId(o.getBatchId());
        c.setCustomerId(o.getCustomerId());
        c.setType(ComplaintType.valueOf(req.getType()));
        c.setProblemWeight(req.getProblemWeight());
        c.setClaimAmount(req.getClaimAmount());
        c.setDescription(req.getDescription());
        c.setResponsibility(Responsibility.PENDING);
        c = complaintRepository.save(c);

        alertService.raise(AlertType.COMPLAINT, AlertLevel.CRITICAL, o.getBatchId(), null,
                "客户投诉 " + c.getComplaintNo(),
                "出库单 " + o.getOrderNo() + " 客户投诉：" + c.getType().desc
                        + (c.getProblemWeight() == null ? "" : "，问题重量 " + c.getProblemWeight() + "kg")
                        + (c.getClaimAmount() == null ? "" : "，索赔 " + c.getClaimAmount() + " 元")
                        + "。需追溯批次链并裁定责任。",
                "客户");
        traceService.log(o.getBatchId(), null, o.getCustomerId(), Stage.COMPLAINT,
                "客户投诉登记 " + c.getComplaintNo(),
                c.getType().desc + "，问题重量 " + c.getProblemWeight() + "kg，索赔 "
                        + c.getClaimAmount() + " 元。" + (c.getDescription() == null ? "" : c.getDescription()),
                "客服");
        return c;
    }

    @Transactional
    public Complaint resolve(Long id, ComplaintResolveRequest req) {
        Complaint c = complaintRepository.findById(id)
                .orElseThrow(() -> new BizException("投诉不存在"));
        c.setResponsibility(Responsibility.valueOf(req.getResponsibility()));
        c.setSettledAmount(req.getSettledAmount());
        c.setHandler(req.getHandler() == null ? "管理端" : req.getHandler());
        c.setHandleResult(req.getHandleResult());
        c.setHandledAt(LocalDateTime.now());
        c.setResolved(true);
        complaintRepository.save(c);

        traceService.log(c.getBatchId(), null, c.getCustomerId(), Stage.COMPLAINT,
                "投诉裁定 " + c.getComplaintNo(),
                "责任方：" + c.getResponsibility().desc + "，裁定赔付 " + c.getSettledAmount()
                        + " 元。" + (c.getHandleResult() == null ? "" : c.getHandleResult())
                        + (c.getResponsibility() == Responsibility.WAREHOUSE
                        ? " 冷库承担的赔付将在货主账单中体现为投诉扣款（由冷库对客户赔付，不扣货主）。"
                        : c.getResponsibility() == Responsibility.OWNER
                        ? " 货主责任，赔付从货主货款中扣除。" : ""),
                c.getHandler());
        return c;
    }

    /**
     * 责任判定辅助：回看批次到货温度超标记录与货主确认记录，
     * 结合在仓温区故障告警，给出责任方建议供管理端裁定参考。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> evidence(Long complaintId) {
        Complaint c = complaintRepository.findById(complaintId)
                .orElseThrow(() -> new BizException("投诉不存在"));
        Batch batch = batchRepository.findById(c.getBatchId())
                .orElseThrow(() -> new BizException("批次不存在"));
        Category cat = categoryRepository.findById(batch.getCategoryId()).orElseThrow();
        List<TempExceptionRecord> records =
                tempExceptionRepository.findByBatchIdOrderByCreatedAtDesc(batch.getId());
        long zoneFaults = alertRepository.findByBatchIdOrderByCreatedAtDesc(batch.getId()).stream()
                .filter(a -> a.getType() == AlertType.ZONE_FAULT).count();
        boolean tempExceeded = TempExceptionService.exceeds(cat, batch.getArrivalTemp());

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("complaintId", c.getId());
        m.put("complaintNo", c.getComplaintNo());
        m.put("batchId", batch.getId());
        m.put("batchNo", batch.getBatchNo());
        m.put("arrivalTemp", batch.getArrivalTemp());
        m.put("contractMinTemp", cat.getMinArrivalTemp());
        m.put("contractMaxTemp", cat.getMaxArrivalTemp());
        m.put("tempExceeded", tempExceeded);
        m.put("zoneFaultAlerts", zoneFaults);

        List<Map<String, Object>> confirmations = new ArrayList<>();
        for (TempExceptionRecord r : records) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("recordNo", r.getRecordNo());
            e.put("measuredTemp", r.getMeasuredTemp());
            e.put("arrivedAt", r.getArrivedAt());
            e.put("sampledRotRate", r.getSampledRotRate());
            e.put("decision", r.getDecision() == null ? null : r.getDecision().name());
            e.put("decisionDesc", r.getDecision() == null ? "待处置" : r.getDecision().desc);
            e.put("decidedBy", r.getDecidedBy());
            e.put("decidedAt", r.getDecidedAt());
            e.put("ownerConfirmNote", r.getOwnerConfirmNote());
            e.put("recheckResult", r.getRecheckResult() == null ? null : r.getRecheckResult().name());
            e.put("recheckResultDesc", r.getRecheckResult() == null ? null : r.getRecheckResult().desc);
            e.put("effectNote", r.getEffectNote());
            confirmations.add(e);
        }
        m.put("tempExceptions", confirmations);

        String suggestion = null;
        StringBuilder reason = new StringBuilder();
        TempExceptionRecord decided = records.stream()
                .filter(r -> r.getDecision() != null).findFirst().orElse(null);
        if (decided != null && decided.getDecision() == TempDecision.DOWNGRADE_ACCEPT) {
            suggestion = Responsibility.OWNER.name();
            reason.append(String.format("到货复测 %s℃ 超合同区间 [%s,%s]℃，货主 %s 于 %s 确认知悉并选择降级入库"
                            + "（%s），坏果属到货品质问题，建议货主责任。",
                    decided.getMeasuredTemp(), cat.getMinArrivalTemp(), cat.getMaxArrivalTemp(),
                    decided.getDecidedBy(), fmt(decided.getDecidedAt()), decided.getEffectNote()));
        } else if (decided != null && decided.getDecision() == TempDecision.PRECOOL_RECHECK
                && decided.getRecheckResult() == RecheckResult.PASSED) {
            suggestion = Responsibility.OWNER.name();
            reason.append(String.format("到货复测 %s℃ 超合同区间，货主 %s 确认选择预冷后复检且复检合格入库"
                            + "（%s），温度超标事实货主已确认，建议货主责任。",
                    decided.getMeasuredTemp(), decided.getDecidedBy(), decided.getEffectNote()));
        } else if (decided != null && decided.getDecision() == TempDecision.PRECOOL_RECHECK
                && decided.getRecheckResult() == RecheckResult.FAILED) {
            suggestion = Responsibility.WAREHOUSE.name();
            reason.append("该批次预冷复检不合格应整批退货，仍流向客户，属冷库放行失误，建议冷库责任。");
        } else if (decided != null && decided.getDecision() == TempDecision.ONSITE_RETURN) {
            suggestion = Responsibility.WAREHOUSE.name();
            reason.append("该批次已现场确认整批退货，仍流向客户，属冷库放行失误，建议冷库责任。");
        } else if (tempExceeded) {
            reason.append(String.format("到货温度 %s℃ 超合同区间 [%s,%s]℃，但无超标处置与货主确认记录，"
                            + "请结合在仓温控与出库交接记录人工判定。",
                    batch.getArrivalTemp(), cat.getMinArrivalTemp(), cat.getMaxArrivalTemp()));
        } else {
            reason.append("到货温度在合同区间内，无温度超标处置记录，按常规流程裁定。");
        }
        if (zoneFaults > 0) {
            reason.append("注意：在仓期间发生温区故障告警 ").append(zoneFaults)
                    .append(" 次，存在冷库侧温控风险，裁定扣减时请权衡。");
        }
        m.put("suggestedResponsibility", suggestion);
        m.put("suggestionReason", reason.toString());
        return m;
    }

    private String fmt(LocalDateTime t) {
        return t == null ? "-" : t.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }

    @Transactional(readOnly = true)
    public List<Complaint> list() {
        return complaintRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<Complaint> byBatch(Long batchId) {
        return complaintRepository.findByBatchIdOrderByCreatedAtDesc(batchId);
    }
}
