package com.county.coldchain.web;

import com.county.coldchain.domain.entity.Complaint;
import com.county.coldchain.service.ComplaintService;
import com.county.coldchain.web.dto.ComplaintRequest;
import com.county.coldchain.web.dto.ComplaintResolveRequest;
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

@Tag(name = "客户投诉")
@RestController
@RequestMapping("/api/complaints")
@RequiredArgsConstructor
public class ComplaintController {

    private final ComplaintService complaintService;

    @Operation(summary = "出库后客户投诉登记（坏果/短重/温度）")
    @PostMapping
    public Complaint create(@Valid @RequestBody ComplaintRequest req) {
        return complaintService.create(req);
    }

    @Operation(summary = "裁定投诉责任与赔付")
    @PostMapping("/{id}/resolve")
    public Complaint resolve(@PathVariable Long id, @Valid @RequestBody ComplaintResolveRequest req) {
        return complaintService.resolve(id, req);
    }

    @Operation(summary = "投诉列表")
    @GetMapping
    public List<Complaint> list() {
        return complaintService.list();
    }

    @Operation(summary = "批次投诉")
    @GetMapping("/batch/{batchId}")
    public List<Complaint> byBatch(@PathVariable Long batchId) {
        return complaintService.byBatch(batchId);
    }

    @Operation(summary = "责任判定证据：回看温度超标处置与货主确认记录，给出责任方建议")
    @GetMapping("/{id}/evidence")
    public java.util.Map<String, Object> evidence(@PathVariable Long id) {
        return complaintService.evidence(id);
    }
}
