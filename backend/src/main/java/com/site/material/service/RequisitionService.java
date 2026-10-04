package com.site.material.service;

import com.site.material.common.BusinessException;
import com.site.material.common.CodeGenerator;
import com.site.material.domain.*;
import com.site.material.dto.Requests.*;
import com.site.material.repo.*;
import com.site.material.security.SecurityUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 领料链路：班组申请 -> 仓管核对（施工进度/预算量/已领量/现场库存）-> 发料 -> 班组长确认。
 */
@Service
public class RequisitionService {

    private final RequisitionRepository reqRepo;
    private final MaterialBatchRepository batchRepo;
    private final ProjectBudgetRepository budgetRepo;
    private final MaterialRepository materialRepo;
    private final CrewRepository crewRepo;
    private final StockCheckService stockCheckService;
    private final CodeGenerator codes;

    public RequisitionService(RequisitionRepository reqRepo, MaterialBatchRepository batchRepo,
                              ProjectBudgetRepository budgetRepo, MaterialRepository materialRepo,
                              CrewRepository crewRepo, StockCheckService stockCheckService,
                              CodeGenerator codes) {
        this.reqRepo = reqRepo;
        this.batchRepo = batchRepo;
        this.budgetRepo = budgetRepo;
        this.materialRepo = materialRepo;
        this.crewRepo = crewRepo;
        this.stockCheckService = stockCheckService;
        this.codes = codes;
    }

    @Transactional
    public Requisition create(RequisitionRequest req) {
        if (req.items() == null || req.items().isEmpty()) {
            throw new BusinessException("领料明细不能为空");
        }
        Crew crew = crewRepo.findById(req.crewId())
                .orElseThrow(() -> new BusinessException("班组不存在"));
        // 盘点追查受限班组：新申请先转项目经理审批，仓管不得直接发料
        Set<Long> frozen = stockCheckService.frozenMaterialIds();
        for (ItemIn it : req.items()) {
            if (frozen.contains(it.materialId())) {
                throw new BusinessException("该材料盘点差异尚未关闭，暂停领用");
            }
        }
        Requisition r = new Requisition();
        r.setCode(codes.next("LL"));
        r.setCrewId(req.crewId());
        r.setBuilding(req.building());
        r.setArea(req.area());
        r.setProcess(req.process());
        r.setDrawingNo(req.drawingNo());
        r.setUrgent(req.urgent());
        r.setRemark(req.remark());
        r.setApplicant(SecurityUtil.currentName());
        r.setApplyTime(LocalDateTime.now());
        r.setStatus(crew.isRequisitionRestricted() ? "PENDING_PM" : "SUBMITTED");
        for (ItemIn it : req.items()) {
            RequisitionItem item = new RequisitionItem();
            item.setRequisition(r);
            item.setMaterialId(it.materialId());
            item.setApplyQty(it.applyQty());
            r.getItems().add(item);
        }
        Requisition saved = reqRepo.save(r);
        if (crew.isRequisitionRestricted()) {
            r.setRemark((r.getRemark() == null ? "" : r.getRemark() + "；")
                    + "该班组存在盘点夜料未登记记录，本单须项目经理审批后仓管方可发料");
        }
        return saved;
    }

    /** 受限班组领料单：项目经理审批通过后回到待发料队列 */
    @Transactional
    public Requisition approve(Long id, String remark) {
        Requisition r = reqRepo.findByIdWithItems(id)
                .orElseThrow(() -> new BusinessException("领料单不存在"));
        if (!"PENDING_PM".equals(r.getStatus())) {
            throw new BusinessException("仅待项目经理审批的单据可审批，当前状态: " + r.getStatus());
        }
        r.setStatus("SUBMITTED");
        r.setPmApprovedBy(SecurityUtil.currentName());
        r.setPmApproveTime(LocalDateTime.now());
        if (remark != null && !remark.isBlank()) {
            r.setRemark((r.getRemark() == null ? "" : r.getRemark() + "；") + "PM审批意见：" + remark);
        }
        return reqRepo.save(r);
    }

    /** 发料前核对：预算量、已领量、现场库存（给前端展示核对结果，不直接改库存） */
    public Map<String, Object> preIssueCheck(Long id) {
        Requisition r = reqRepo.findByIdWithItems(id).orElseThrow(() -> new BusinessException("领料单不存在"));
        Map<Long, Material> mats = materialRepo.findAll().stream()
                .collect(Collectors.toMap(Material::getId, Function.identity()));
        Map<Long, String> frozenCodes = stockCheckService.frozenCheckCodes();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (RequisitionItem it : r.getItems()) {
            Material m = mats.get(it.getMaterialId());
            List<MaterialBatch> batches = batchRepo.findByMaterialIdOrderByInboundTimeDesc(it.getMaterialId())
                    .stream().filter(b -> !"FAILED".equals(b.getQcStatus())).toList();
            double onHand = batches.stream().mapToDouble(MaterialBatch::onHand).sum();

            List<ProjectBudget> budgets = budgetRepo.findByBuildingAndProcessAndMaterialId(
                    r.getBuilding(), r.getProcess(), it.getMaterialId());
            double budgetQty = budgets.stream()
                    .filter(b -> b.getCrewId() == null || b.getCrewId().equals(r.getCrewId()))
                    .mapToDouble(ProjectBudget::getBudgetQty).sum();
            double alreadyIssued = issuedAlready(r, it.getMaterialId());

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("itemId", it.getId());
            row.put("materialId", it.getMaterialId());
            row.put("materialName", m == null ? null : m.getName());
            row.put("unit", m == null ? null : m.getUnit());
            row.put("applyQty", it.getApplyQty());
            row.put("budgetQty", MasterDataService.round(budgetQty));
            row.put("alreadyIssued", MasterDataService.round(alreadyIssued));
            row.put("onHandQty", MasterDataService.round(onHand));
            row.put("stockEnough", onHand + 1e-9 >= it.getApplyQty());
            row.put("frozenByCheck", frozenCodes.get(it.getMaterialId()));
            row.put("overBudget", budgetQty > 0 && alreadyIssued + it.getApplyQty() > budgetQty + 1e-9);
            row.put("batches", batches.stream().map(b -> Map.of(
                    "batchId", b.getId(), "batchNo", b.getBatchNo(),
                    "onHand", b.onHand(), "unitPrice", b.getUnitPrice(),
                    "qcStatus", b.getQcStatus())).toList());
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requisition", view(r));
        out.put("checks", rows);
        out.put("crewRestricted", crewRepo.findById(r.getCrewId())
                .map(Crew::isRequisitionRestricted).orElse(false));
        return out;
    }

    private double issuedAlready(Requisition current, Long materialId) {
        return reqRepo.findAllWithItems().stream()
                .filter(x -> "ISSUED".equals(x.getStatus()))
                .filter(x -> x.getCrewId().equals(current.getCrewId()))
                .filter(x -> Objects.equals(x.getBuilding(), current.getBuilding()))
                .filter(x -> Objects.equals(x.getProcess(), current.getProcess()))
                .flatMap(x -> x.getItems().stream())
                .filter(i -> i.getMaterialId().equals(materialId))
                .mapToDouble(i -> i.getIssuedQty() == null ? 0d : i.getIssuedQty())
                .sum();
    }

    @Transactional
    public Map<String, Object> issue(Long id, IssueRequest req) {
        Requisition r = reqRepo.findByIdWithItems(id).orElseThrow(() -> new BusinessException("领料单不存在"));
        if (!"SUBMITTED".equals(r.getStatus())) {
            throw new BusinessException("仅待发料单据可执行发料，当前状态: "
                    + ("PENDING_PM".equals(r.getStatus()) ? "待项目经理审批" : r.getStatus()));
        }
        Map<Long, IssueItemIn> input = req.items().stream()
                .collect(Collectors.toMap(IssueItemIn::itemId, Function.identity()));
        Map<Long, String> frozen = stockCheckService.frozenCheckCodes();
        List<String> warnings = new ArrayList<>();

        for (RequisitionItem item : r.getItems()) {
            if (frozen.containsKey(item.getMaterialId())) {
                throw new BusinessException("材料处于盘点差异冻结期（盘点单 "
                        + frozen.get(item.getMaterialId()) + "），差异关闭前暂停领用");
            }
            IssueItemIn in = input.get(item.getId());
            if (in == null || in.issuedQty() == null || in.issuedQty() <= 0) {
                throw new BusinessException("明细 " + item.getId() + " 未填写实发数量");
            }
            if (in.batchId() == null) {
                throw new BusinessException("请选择发料批次");
            }
            MaterialBatch batch = batchRepo.findById(in.batchId())
                    .orElseThrow(() -> new BusinessException("批次不存在"));
            if (!batch.getMaterialId().equals(item.getMaterialId())) {
                throw new BusinessException("批次材料与领料明细不一致");
            }
            if ("FAILED".equals(batch.getQcStatus()) || "PENDING".equals(batch.getQcStatus())) {
                throw new BusinessException("批次 " + batch.getBatchNo() + " 质检状态为 "
                        + batch.getQcStatus() + "，不能发料");
            }
            if (batch.onHand() + 1e-9 < in.issuedQty()) {
                throw new BusinessException("批次 " + batch.getBatchNo() + " 现场库存不足："
                        + MasterDataService.round(batch.onHand()));
            }
            batch.setQtyIssued(batch.getQtyIssued() + in.issuedQty());
            item.setBatchId(batch.getId());
            item.setIssuedQty(in.issuedQty());
            item.setIssuePrice(batch.getUnitPrice());

            double already = issuedAlready(r, item.getMaterialId());
            double budgetQty = budgetRepo.findByBuildingAndProcessAndMaterialId(
                            r.getBuilding(), r.getProcess(), item.getMaterialId()).stream()
                    .filter(b -> b.getCrewId() == null || b.getCrewId().equals(r.getCrewId()))
                    .mapToDouble(ProjectBudget::getBudgetQty).sum();
            if (budgetQty > 0 && already + in.issuedQty() > budgetQty + 1e-9 && !r.isUrgent()) {
                warnings.add("材料 " + item.getMaterialId() + " 累计领用已超预算，需补办审批");
            }
        }
        r.setStatus("ISSUED");
        r.setIssuedBy(SecurityUtil.currentName());
        r.setConfirmedBy(r.getApplicant()); // 班组长确认领用
        r.setIssueTime(LocalDateTime.now());
        if (req.remark() != null) {
            r.setRemark(req.remark());
        }
        reqRepo.save(r);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requisition", view(r));
        out.put("warnings", warnings);
        return out;
    }

    @Transactional
    public Requisition reject(Long id, String remark) {
        Requisition r = reqRepo.findByIdWithItems(id).orElseThrow(() -> new BusinessException("领料单不存在"));
        if (!"SUBMITTED".equals(r.getStatus()) && !"PENDING_PM".equals(r.getStatus())) {
            throw new BusinessException("仅待发料/待审批单据可驳回");
        }
        r.setStatus("REJECTED");
        r.setRemark(remark);
        return r;
    }

    public List<Map<String, Object>> list(String status, Long crewId) {
        List<Requisition> all = reqRepo.findAllWithItems();
        return all.stream()
                .filter(r -> status == null || status.equals(r.getStatus()))
                .filter(r -> crewId == null || crewId.equals(r.getCrewId()))
                .map(this::view)
                .toList();
    }

    public Map<String, Object> detail(Long id) {
        Requisition r = reqRepo.findByIdWithItems(id).orElseThrow(() -> new BusinessException("领料单不存在"));
        return view(r);
    }

    public Map<String, Object> view(Requisition r) {
        Map<Long, String> matNames = materialRepo.findAll().stream()
                .collect(Collectors.toMap(Material::getId, Material::getName));
        Map<Long, String> units = materialRepo.findAll().stream()
                .collect(Collectors.toMap(Material::getId, Material::getUnit));
        Map<Long, String> crewNames = crewRepo.findAll().stream()
                .collect(Collectors.toMap(Crew::getId, Crew::getName));
        Map<Long, String> batchNos = batchRepo.findAll().stream()
                .collect(Collectors.toMap(MaterialBatch::getId, MaterialBatch::getBatchNo));

        List<Map<String, Object>> items = r.getItems().stream().map(i -> {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("itemId", i.getId());
            x.put("materialId", i.getMaterialId());
            x.put("materialName", matNames.get(i.getMaterialId()));
            x.put("unit", units.get(i.getMaterialId()));
            x.put("applyQty", i.getApplyQty());
            x.put("issuedQty", i.getIssuedQty());
            x.put("batchId", i.getBatchId());
            x.put("batchNo", i.getBatchId() == null ? null : batchNos.get(i.getBatchId()));
            x.put("issuePrice", i.getIssuePrice());
            x.put("amount", MasterDataService.round(
                    (i.getIssuedQty() == null ? 0d : i.getIssuedQty()) * i.getIssuePrice()));
            return x;
        }).toList();

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("code", r.getCode());
        m.put("crewId", r.getCrewId());
        m.put("crewName", crewNames.get(r.getCrewId()));
        m.put("building", r.getBuilding());
        m.put("area", r.getArea());
        m.put("process", r.getProcess());
        m.put("drawingNo", r.getDrawingNo());
        m.put("status", r.getStatus());
        m.put("urgent", r.isUrgent());
        m.put("backdated", r.isBackdated());
        m.put("sourceCheckId", r.getSourceCheckId());
        m.put("applicant", r.getApplicant());
        m.put("issuedBy", r.getIssuedBy());
        m.put("confirmedBy", r.getConfirmedBy());
        m.put("pmApprovedBy", r.getPmApprovedBy());
        m.put("pmApproveTime", r.getPmApproveTime());
        m.put("applyTime", r.getApplyTime());
        m.put("issueTime", r.getIssueTime());
        m.put("actualUseTime", r.getActualUseTime());
        m.put("crewRestricted", crewRepo.findById(r.getCrewId())
                .map(Crew::isRequisitionRestricted).orElse(false));
        m.put("remark", r.getRemark());
        m.put("items", items);
        m.put("totalAmount", MasterDataService.round(items.stream()
                .mapToDouble(x -> (double) x.get("amount")).sum()));
        return m;
    }
}
