package com.site.material.service;

import com.site.material.common.BusinessException;
import com.site.material.common.CodeGenerator;
import com.site.material.domain.*;
import com.site.material.dto.Requests.*;
import com.site.material.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 主数据与库存：材料、供应商、班组、入库批次（含质检）、项目预算。
 */
@Service
public class MasterDataService {

    private final MaterialRepository materialRepo;
    private final SupplierRepository supplierRepo;
    private final CrewRepository crewRepo;
    private final MaterialBatchRepository batchRepo;
    private final ProjectBudgetRepository budgetRepo;
    private final StockCheckService stockCheckService;
    private final CodeGenerator codes;

    public MasterDataService(MaterialRepository materialRepo, SupplierRepository supplierRepo,
                             CrewRepository crewRepo, MaterialBatchRepository batchRepo,
                             ProjectBudgetRepository budgetRepo, StockCheckService stockCheckService,
                             CodeGenerator codes) {
        this.materialRepo = materialRepo;
        this.supplierRepo = supplierRepo;
        this.crewRepo = crewRepo;
        this.batchRepo = batchRepo;
        this.budgetRepo = budgetRepo;
        this.stockCheckService = stockCheckService;
        this.codes = codes;
    }

    // ---------- 材料 ----------
    public List<Material> materials() {
        return materialRepo.findByActiveTrueOrderByCategoryAscNameAsc();
    }

    @Transactional
    public Material createMaterial(MaterialRequest req) {
        Material m = new Material();
        m.setName(req.name());
        m.setCategory(req.category());
        m.setSpec(req.spec());
        m.setUnit(req.unit());
        m.setSafetyStock(req.safetyStock() == null ? 0d : req.safetyStock());
        return materialRepo.save(m);
    }

    // ---------- 供应商 ----------
    public List<Supplier> suppliers() {
        return supplierRepo.findAll();
    }

    @Transactional
    public Supplier createSupplier(SupplierRequest req) {
        Supplier s = new Supplier();
        s.setName(req.name());
        s.setContact(req.contact());
        s.setPhone(req.phone());
        return supplierRepo.save(s);
    }

    // ---------- 班组 ----------
    public List<Crew> crews() {
        return crewRepo.findAll();
    }

    @Transactional
    public Crew createCrew(CrewRequest req) {
        Crew c = new Crew();
        c.setName(req.name());
        c.setLeader(req.leader());
        c.setTrade(req.trade());
        return crewRepo.save(c);
    }

    /** 解除盘点夜料未登记导致的领料加严限制（项目经理） */
    @Transactional
    public Crew releaseCrewRestriction(Long crewId) {
        Crew c = crewRepo.findById(crewId).orElseThrow(() -> new BusinessException("班组不存在"));
        c.setRequisitionRestricted(false);
        c.setRestrictReason(null);
        return c;
    }

    // ---------- 入库批次 / 质检 ----------
    @Transactional
    public MaterialBatch inbound(InboundRequest req) {
        Material m = materialRepo.findById(req.materialId())
                .orElseThrow(() -> new BusinessException("材料不存在: " + req.materialId()));
        MaterialBatch b = new MaterialBatch();
        b.setBatchNo(codes.next("RK"));
        b.setMaterialId(req.materialId());
        b.setSpec(req.spec() != null ? req.spec() : m.getSpec());
        b.setSupplierId(req.supplierId());
        b.setQtyInbound(req.qtyInbound());
        b.setUnitPrice(req.unitPrice() == null ? 0d : req.unitPrice());
        b.setQcStatus(req.qcStatus() == null ? "PENDING" : req.qcStatus());
        b.setInboundTime(LocalDateTime.now());
        b.setRemark(req.remark());
        return batchRepo.save(b);
    }

    @Transactional
    public MaterialBatch updateQc(Long id, String qcStatus) {
        MaterialBatch b = batchRepo.findById(id).orElseThrow(() -> new BusinessException("批次不存在"));
        if (!Set.of("PENDING", "PASSED", "FAILED", "CONDITIONAL").contains(qcStatus)) {
            throw new BusinessException("质检状态非法: " + qcStatus);
        }
        b.setQcStatus(qcStatus);
        return b;
    }

    public List<Map<String, Object>> batches(Long materialId) {
        List<MaterialBatch> list = materialId == null ? batchRepo.findAll()
                : batchRepo.findByMaterialIdOrderByInboundTimeDesc(materialId);
        Map<Long, String> suppliers = supplierRepo.findAll().stream()
                .collect(Collectors.toMap(Supplier::getId, Supplier::getName));
        Map<Long, Material> mats = materialRepo.findAll().stream()
                .collect(Collectors.toMap(Material::getId, Function.identity()));
        List<Map<String, Object>> out = new ArrayList<>();
        for (MaterialBatch b : list) {
            Material m = mats.get(b.getMaterialId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", b.getId());
            row.put("batchNo", b.getBatchNo());
            row.put("materialId", b.getMaterialId());
            row.put("materialName", m == null ? null : m.getName());
            row.put("category", m == null ? null : m.getCategory());
            row.put("spec", b.getSpec());
            row.put("unit", m == null ? null : m.getUnit());
            row.put("supplierId", b.getSupplierId());
            row.put("supplierName", b.getSupplierId() == null ? null : suppliers.get(b.getSupplierId()));
            row.put("qtyInbound", b.getQtyInbound());
            row.put("qtyIssued", b.getQtyIssued());
            row.put("qtyReturned", b.getQtyReturned());
            row.put("qtyWasted", b.getQtyWasted());
            row.put("qtyAdjust", b.getQtyAdjust());
            row.put("onHand", b.onHand());
            row.put("unitPrice", b.getUnitPrice());
            row.put("amount", round(b.getQtyInbound() * b.getUnitPrice()));
            row.put("qcStatus", b.getQcStatus());
            row.put("inboundTime", b.getInboundTime());
            row.put("remark", b.getRemark());
            out.add(row);
        }
        return out;
    }

    /** 库存总览：按材料汇总现场库存、批次明细、预警、质检待办、盘点冻结 */
    public Map<String, Object> inventory() {
        List<Material> mats = materialRepo.findAll();
        List<MaterialBatch> all = batchRepo.findAll();
        Map<Long, List<MaterialBatch>> byMaterial = all.stream()
                .collect(Collectors.groupingBy(MaterialBatch::getMaterialId));
        Map<Long, String> frozenCodes = stockCheckService.frozenCheckCodes();
        List<Map<String, Object>> rows = new ArrayList<>();
        double totalValue = 0d;
        int lowCount = 0;
        for (Material m : mats) {
            List<MaterialBatch> bs = byMaterial.getOrDefault(m.getId(), List.of());
            double onHand = bs.stream().mapToDouble(MaterialBatch::onHand).sum();
            double usable = bs.stream().filter(b -> !"FAILED".equals(b.getQcStatus()))
                    .mapToDouble(MaterialBatch::onHand).sum();
            double value = bs.stream().mapToDouble(b -> b.onHand() * b.getUnitPrice()).sum();
            totalValue += value;
            boolean low = usable <= (m.getSafetyStock() == null ? 0d : m.getSafetyStock());
            if (low) {
                lowCount++;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("materialId", m.getId());
            row.put("materialName", m.getName());
            row.put("category", m.getCategory());
            row.put("spec", m.getSpec());
            row.put("unit", m.getUnit());
            row.put("onHand", round(onHand));
            row.put("usableQty", round(usable));
            row.put("safetyStock", m.getSafetyStock());
            row.put("lowStock", low);
            row.put("stockValue", round(value));
            row.put("batchCount", bs.size());
            row.put("pendingQc", bs.stream().filter(b -> "PENDING".equals(b.getQcStatus())).count());
            row.put("frozenByCheck", frozenCodes.get(m.getId()));
            rows.add(row);
        }
        long pendingQc = all.stream().filter(b -> "PENDING".equals(b.getQcStatus())).count();
        return Map.of("rows", rows, "totalStockValue", round(totalValue),
                "lowStockCount", lowCount, "pendingQcCount", pendingQc,
                "frozenCount", frozenCodes.size());
    }

    // ---------- 预算 ----------
    public List<ProjectBudget> budgets() {
        return budgetRepo.findAll();
    }

    @Transactional
    public ProjectBudget createBudget(BudgetRequest req) {
        materialRepo.findById(req.materialId()).orElseThrow(() -> new BusinessException("材料不存在"));
        ProjectBudget b = new ProjectBudget();
        b.setBuilding(req.building());
        b.setArea(req.area());
        b.setProcess(req.process());
        b.setDrawingNo(req.drawingNo());
        b.setMaterialId(req.materialId());
        b.setBudgetQty(req.budgetQty());
        b.setBudgetPrice(req.budgetPrice() == null ? 0d : req.budgetPrice());
        b.setCrewId(req.crewId());
        return budgetRepo.save(b);
    }

    static double round(double v) {
        return Math.round(v * 100d) / 100d;
    }
}
