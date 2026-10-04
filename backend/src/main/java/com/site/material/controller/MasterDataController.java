package com.site.material.controller;

import com.site.material.domain.MaterialBatch;
import com.site.material.dto.Requests.*;
import com.site.material.service.MasterDataService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class MasterDataController {

    private final MasterDataService service;

    public MasterDataController(MasterDataService service) {
        this.service = service;
    }

    // ---------- 材料 ----------
    @GetMapping("/materials")
    public Object materials() {
        return service.materials();
    }

    @PostMapping("/materials")
    @PreAuthorize("hasAnyRole('KEEPER','PM')")
    public Object createMaterial(@Valid @RequestBody MaterialRequest req) {
        return service.createMaterial(req);
    }

    // ---------- 供应商 ----------
    @GetMapping("/suppliers")
    public Object suppliers() {
        return service.suppliers();
    }

    @PostMapping("/suppliers")
    @PreAuthorize("hasAnyRole('KEEPER','PM')")
    public Object createSupplier(@Valid @RequestBody SupplierRequest req) {
        return service.createSupplier(req);
    }

    // ---------- 班组 ----------
    @GetMapping("/crews")
    public Object crews() {
        return service.crews();
    }

    @PostMapping("/crews")
    @PreAuthorize("hasAnyRole('KEEPER','PM')")
    public Object createCrew(@Valid @RequestBody CrewRequest req) {
        return service.createCrew(req);
    }

    /** 设置/解除班组领料审批限制 */
    @PutMapping("/crews/{id}/restriction")
    @PreAuthorize("hasRole('PM')")
    public Object updateCrewRestriction(@PathVariable Long id,
                                        @Valid @RequestBody CrewRestrictionRequest req) {
        return service.updateCrewRestriction(id, req);
    }

    // ---------- 入库批次 / 质检 ----------
    @GetMapping("/batches")
    public List<Map<String, Object>> batches(@RequestParam(required = false) Long materialId) {
        return service.batches(materialId);
    }

    @PostMapping("/batches/inbound")
    @PreAuthorize("hasAnyRole('KEEPER','PM')")
    public MaterialBatch inbound(@Valid @RequestBody InboundRequest req) {
        return service.inbound(req);
    }

    @PutMapping("/batches/{id}/qc")
    @PreAuthorize("hasAnyRole('KEEPER','PM')")
    public MaterialBatch qc(@PathVariable Long id, @RequestBody QcRequest req) {
        return service.updateQc(id, req.qcStatus());
    }

    // ---------- 库存总览 ----------
    @GetMapping("/inventory")
    public Map<String, Object> inventory() {
        return service.inventory();
    }

    // ---------- 预算 ----------
    @GetMapping("/budgets")
    public Object budgets() {
        return service.budgets();
    }

    @PostMapping("/budgets")
    @PreAuthorize("hasAnyRole('BUDGET','PM')")
    public Object createBudget(@Valid @RequestBody BudgetRequest req) {
        return service.createBudget(req);
    }
}
