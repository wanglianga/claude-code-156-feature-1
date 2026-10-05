package com.county.coldchain.service;

import com.county.coldchain.domain.DomainEnums.AlertLevel;
import com.county.coldchain.domain.DomainEnums.AlertType;
import com.county.coldchain.domain.DomainEnums.ComplaintType;
import com.county.coldchain.domain.DomainEnums.Responsibility;
import com.county.coldchain.domain.DomainEnums.Stage;
import com.county.coldchain.domain.entity.Alert;
import com.county.coldchain.domain.entity.Complaint;
import com.county.coldchain.domain.entity.Inspection;
import com.county.coldchain.domain.entity.OutboundOrder;
import com.county.coldchain.domain.entity.TempExceedCase;
import com.county.coldchain.repo.AlertRepository;
import com.county.coldchain.repo.ComplaintRepository;
import com.county.coldchain.repo.InspectionRepository;
import com.county.coldchain.repo.OutboundOrderRepository;
import com.county.coldchain.repo.TempExceedCaseRepository;
import com.county.coldchain.support.BizException;
import com.county.coldchain.web.dto.ComplaintRequest;
import com.county.coldchain.web.dto.ComplaintResolveRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 出库后客户投诉：坏果等问题回挂批次链，裁定赔付进入货主账单扣款。 */
@Service
@RequiredArgsConstructor
public class ComplaintService {

    private final ComplaintRepository complaintRepository;
    private final OutboundOrderRepository outboundRepository;
    private final TempExceedCaseRepository tempCaseRepository;
    private final InspectionRepository inspectionRepository;
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

    @Transactional(readOnly = true)
    public List<Complaint> list() {
        return complaintRepository.findAll();
    }

    /**
     * 责任判定证据：平台回看该批次温度超标处置单（含货主确认记录）、质检记录与相关告警，
     * 并据此给出责任方建议，辅助管理端裁定。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> evidence(Long complaintId) {
        Complaint c = complaintRepository.findById(complaintId)
                .orElseThrow(() -> new BizException("投诉不存在"));
        List<TempExceedCase> cases = tempCaseRepository.findByBatchIdOrderByCreatedAtDesc(c.getBatchId());
        List<Inspection> inspections = inspectionRepository.findByBatchIdOrderByInspectedAtDesc(c.getBatchId());
        List<Alert> alerts = alertRepository.findByBatchIdOrderByCreatedAtDesc(c.getBatchId());

        Responsibility suggested = Responsibility.PENDING;
        String reason = "本批次无到货温度超标处置记录，请按常规追溯（在仓温控/出库交接/运输环节）裁定。";
        TempExceedCase decided = cases.stream()
                .filter(x -> x.getOwnerDecision() != null).findFirst().orElse(null);
        if (decided != null) {
            String rot = decided.getSampleRotRate() == null ? "未抽检"
                    : decided.getSampleRotRate().multiply(BigDecimal.valueOf(100))
                    .setScale(1, RoundingMode.HALF_UP) + "%";
            switch (decided.getOwnerDecision()) {
                case DOWNGRADE -> {
                    suggested = Responsibility.OWNER;
                    reason = String.format(
                            "处置单 %s：到库温度 %s℃ 超合同区间 [%s,%s]℃，抽检腐损率 %s；货主「%s」已确认降级入库"
                                    + "（售价折让、保鲜期缩短），坏果属到货质量风险，建议裁定货主责任。",
                            decided.getCaseNo(), decided.getArrivalTemp(),
                            decided.getContractMinTemp(), decided.getContractMaxTemp(), rot,
                            decided.getDecidedBy());
                }
                case RETURN -> {
                    suggested = Responsibility.OWNER;
                    reason = String.format(
                            "处置单 %s：货主「%s」已确认温度超标现场退货，若仍有该批货品流出属货源问题，建议裁定货主责任。",
                            decided.getCaseNo(), decided.getDecidedBy());
                }
                case PRECOOL -> {
                    if ("FAIL".equals(decided.getRecheckResult())) {
                        suggested = Responsibility.WAREHOUSE;
                        reason = String.format(
                                "处置单 %s：预冷复检不合格（%s℃），该批次应撤回不得销售；若仍流出到客户，"
                                        + "属冷库冻结/管控失职，建议裁定冷库责任。",
                                decided.getCaseNo(), decided.getRecheckTemp());
                    } else if ("PASS".equals(decided.getRecheckResult())) {
                        reason = String.format(
                                "处置单 %s：预冷复检合格（%s℃）后出库，到货温度风险已消除，"
                                        + "建议核查在仓温控与运输环节后裁定。",
                                decided.getCaseNo(), decided.getRecheckTemp());
                    }
                }
            }
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("complaint", c);
        m.put("tempCases", cases);
        m.put("inspections", inspections);
        m.put("alerts", alerts);
        m.put("suggestedResponsibility", suggested.name());
        m.put("suggestionReason", reason);
        return m;
    }

    @Transactional(readOnly = true)
    public List<Complaint> byBatch(Long batchId) {
        return complaintRepository.findByBatchIdOrderByCreatedAtDesc(batchId);
    }
}
