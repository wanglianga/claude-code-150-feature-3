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
import java.util.stream.Collectors;

/**
 * 盘点链路：登记账面/实盘差异 -> 差异追查（入库/领用/退库/损耗/监控交接）->
 * 按结论补录班组领料或转项目公共损耗 -> 项目经理确认进度影响 -> 关闭调账。
 * 盘亏差异关闭前，相关材料限制继续领用；补录领料计入班组成本并触发班组领料审批限制。
 */
@Service
public class StockCheckService {

    private final StockCheckRepository checkRepo;
    private final MaterialBatchRepository batchRepo;
    private final MaterialRepository materialRepo;
    private final RequisitionRepository reqRepo;
    private final MaterialReturnRepository returnRepo;
    private final WasteRepository wasteRepo;
    private final HandoverRecordRepository handoverRepo;
    private final CrewRepository crewRepo;
    private final SupplierRepository supplierRepo;
    private final CodeGenerator codes;

    public StockCheckService(StockCheckRepository checkRepo, MaterialBatchRepository batchRepo,
                             MaterialRepository materialRepo, RequisitionRepository reqRepo,
                             MaterialReturnRepository returnRepo, WasteRepository wasteRepo,
                             HandoverRecordRepository handoverRepo, CrewRepository crewRepo,
                             SupplierRepository supplierRepo, CodeGenerator codes) {
        this.checkRepo = checkRepo;
        this.batchRepo = batchRepo;
        this.materialRepo = materialRepo;
        this.reqRepo = reqRepo;
        this.returnRepo = returnRepo;
        this.wasteRepo = wasteRepo;
        this.handoverRepo = handoverRepo;
        this.crewRepo = crewRepo;
        this.supplierRepo = supplierRepo;
        this.codes = codes;
    }

    @Transactional
    public StockCheck create(StockCheckRequest req) {
        if (req.items() == null || req.items().isEmpty()) {
            throw new BusinessException("盘点明细不能为空");
        }
        StockCheck sc = new StockCheck();
        sc.setCode(codes.next("PD"));
        sc.setScope(req.scope());
        sc.setRemark(req.remark());
        sc.setRecorder(SecurityUtil.currentName());
        sc.setCreatedTime(LocalDateTime.now());
        sc.setStatus("COUNTING");

        Map<Long, List<MaterialBatch>> batches = batchRepo.findAll().stream()
                .collect(Collectors.groupingBy(MaterialBatch::getMaterialId));
        for (CheckItemIn in : req.items()) {
            List<MaterialBatch> bs = batches.getOrDefault(in.materialId(), List.of());
            MaterialBatch target = in.batchId() != null
                    ? bs.stream().filter(b -> b.getId().equals(in.batchId())).findFirst().orElse(null)
                    : bs.stream().findFirst().orElse(null);
            if (target == null) {
                throw new BusinessException("材料 " + in.materialId() + " 无入库批次，无法盘点");
            }
            StockCheckItem item = new StockCheckItem();
            item.setStockCheck(sc);
            item.setMaterialId(in.materialId());
            item.setBatchId(target.getId());
            item.setBookQty(target.onHand());
            item.setActualQty(in.actualQty());
            item.setUnitPrice(target.getUnitPrice());
            item.setResponsibility(in.responsibility() == null ? "COMPANY" : in.responsibility());
            item.setRemark(in.remark());
            sc.getItems().add(item);
        }
        return checkRepo.save(sc);
    }

    /** 差异追查：按材料汇总入库、领用、退库、损耗登记与监控交接记录，供定位责任 */
    public Map<String, Object> trace(Long id) {
        StockCheck sc = checkRepo.findByIdWithItems(id)
                .orElseThrow(() -> new BusinessException("盘点单不存在"));
        Map<Long, Material> mats = materialRepo.findAll().stream()
                .collect(Collectors.toMap(Material::getId, x -> x));
        Map<Long, String> crewNames = crewRepo.findAll().stream()
                .collect(Collectors.toMap(Crew::getId, Crew::getName));
        Map<Long, String> supplierNames = supplierRepo.findAll().stream()
                .collect(Collectors.toMap(Supplier::getId, Supplier::getName));

        List<Map<String, Object>> items = new ArrayList<>();
        for (StockCheckItem item : sc.getItems()) {
            Long mid = item.getMaterialId();
            Material m = mats.get(mid);

            List<Map<String, Object>> inbound = batchRepo
                    .findByMaterialIdOrderByInboundTimeDesc(mid).stream().map(b -> {
                        Map<String, Object> x = new LinkedHashMap<>();
                        x.put("batchId", b.getId());
                        x.put("batchNo", b.getBatchNo());
                        x.put("spec", b.getSpec());
                        x.put("supplier", b.getSupplierId() == null ? null : supplierNames.get(b.getSupplierId()));
                        x.put("qtyInbound", b.getQtyInbound());
                        x.put("qtyIssued", MasterDataService.round(b.getQtyIssued()));
                        x.put("qtyReturned", MasterDataService.round(b.getQtyReturned()));
                        x.put("qtyWasted", MasterDataService.round(b.getQtyWasted()));
                        x.put("qtyAdjust", MasterDataService.round(b.getQtyAdjust()));
                        x.put("onHand", MasterDataService.round(b.onHand()));
                        x.put("unitPrice", b.getUnitPrice());
                        x.put("qcStatus", b.getQcStatus());
                        x.put("inboundTime", b.getInboundTime());
                        return x;
                    }).toList();

            List<Map<String, Object>> requisitions = reqRepo.findAllWithItems().stream()
                    .filter(r -> "ISSUED".equals(r.getStatus()))
                    .filter(r -> r.getItems().stream().anyMatch(i -> i.getMaterialId().equals(mid)))
                    .map(r -> {
                        double qty = r.getItems().stream()
                                .filter(i -> i.getMaterialId().equals(mid))
                                .mapToDouble(i -> i.getIssuedQty() == null ? 0d : i.getIssuedQty()).sum();
                        Map<String, Object> x = new LinkedHashMap<>();
                        x.put("id", r.getId());
                        x.put("code", r.getCode());
                        x.put("crewName", crewNames.get(r.getCrewId()));
                        x.put("building", r.getBuilding());
                        x.put("process", r.getProcess());
                        x.put("qty", MasterDataService.round(qty));
                        x.put("urgent", r.isUrgent());
                        x.put("backfill", r.isBackfill());
                        x.put("issueTime", r.getIssueTime());
                        return x;
                    }).toList();

            List<Map<String, Object>> returns = returnRepo.findAllWithItems().stream()
                    .filter(r -> "CONFIRMED".equals(r.getStatus()))
                    .filter(r -> r.getItems().stream().anyMatch(i -> i.getMaterialId().equals(mid)))
                    .map(r -> {
                        double qty = r.getItems().stream()
                                .filter(i -> i.getMaterialId().equals(mid))
                                .mapToDouble(ReturnItem::getQty).sum();
                        Map<String, Object> x = new LinkedHashMap<>();
                        x.put("code", r.getCode());
                        x.put("crewName", crewNames.get(r.getCrewId()));
                        x.put("qty", MasterDataService.round(qty));
                        x.put("reasonType", r.getReasonType());
                        x.put("confirmTime", r.getConfirmTime());
                        return x;
                    }).toList();

            List<Map<String, Object>> wastes = wasteRepo.findAllByOrderByCreatedTimeDesc().stream()
                    .filter(w -> w.getMaterialId().equals(mid))
                    .map(w -> {
                        Map<String, Object> x = new LinkedHashMap<>();
                        x.put("code", w.getCode());
                        x.put("crewName", w.getCrewId() == null ? null : crewNames.get(w.getCrewId()));
                        x.put("qty", w.getQty());
                        x.put("wasteType", w.getWasteType());
                        x.put("responsibility", w.getResponsibility());
                        x.put("description", w.getDescription());
                        x.put("createdTime", w.getCreatedTime());
                        return x;
                    }).toList();

            Map<String, Object> x = new LinkedHashMap<>();
            x.put("itemId", item.getId());
            x.put("materialId", mid);
            x.put("materialName", m == null ? null : m.getName());
            x.put("unit", m == null ? null : m.getUnit());
            x.put("batchId", item.getBatchId());
            x.put("bookQty", item.getBookQty());
            x.put("actualQty", item.getActualQty());
            x.put("diffQty", MasterDataService.round(item.diffQty()));
            x.put("lossQty", MasterDataService.round(item.lossQty()));
            x.put("backfillQty", MasterDataService.round(item.getBackfillQty()));
            x.put("commonLossQty", MasterDataService.round(item.getCommonLossQty()));
            x.put("remainingQty", MasterDataService.round(item.remainingQty()));
            x.put("unitPrice", item.getUnitPrice());
            x.put("responsibility", item.getResponsibility());
            x.put("remark", item.getRemark());
            x.put("backfillRequisitionId", item.getBackfillRequisitionId());
            x.put("inbound", inbound);
            x.put("requisitions", requisitions);
            x.put("returns", returns);
            x.put("wastes", wastes);
            items.add(x);
        }

        List<Map<String, Object>> handovers = handoverRepo.findAllByOrderByHandoverTimeDesc().stream()
                .map(h -> handoverView(h, crewNames, mats))
                .toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("check", view(sc));
        out.put("items", items);
        out.put("handovers", handovers);
        return out;
    }

    /** 追查结论一：发现班组未登记领料（如夜间领料），平台补录领料单并限制该班组后续领料审批 */
    @Transactional
    public Map<String, Object> backfill(Long id, Long itemId, BackfillRequest req) {
        StockCheck sc = checkRepo.findByIdWithItems(id)
                .orElseThrow(() -> new BusinessException("盘点单不存在"));
        StockCheckItem item = lossItem(sc, itemId);
        double qty = req.qty() == null ? 0d : req.qty();
        if (qty <= 0) {
            throw new BusinessException("补录数量必须大于 0");
        }
        if (qty > item.remainingQty() + 1e-9) {
            throw new BusinessException("补录数量超过待处理差异，剩余 "
                    + MasterDataService.round(item.remainingQty()));
        }
        Crew crew = crewRepo.findById(req.crewId())
                .orElseThrow(() -> new BusinessException("班组不存在"));
        MaterialBatch batch = batchRepo.findById(item.getBatchId())
                .orElseThrow(() -> new BusinessException("批次不存在"));

        // 补录领料单：直接记为已发料（夜间先发后补），计入班组成本
        Requisition r = new Requisition();
        r.setCode(codes.next("LL"));
        r.setCrewId(crew.getId());
        r.setBuilding(req.building());
        r.setArea(req.area());
        r.setProcess(req.process());
        r.setStatus("ISSUED");
        r.setUrgent(true);
        r.setBackfill(true);
        r.setApplicant(crew.getLeader());
        r.setIssuedBy(SecurityUtil.currentName());
        r.setConfirmedBy(crew.getLeader());
        r.setApplyTime(LocalDateTime.now());
        r.setIssueTime(LocalDateTime.now());
        r.setRemark("盘点补录：未登记领料（盘点单 " + sc.getCode() + "）"
                + (req.remark() == null || req.remark().isBlank() ? "" : "；" + req.remark()));
        RequisitionItem ri = new RequisitionItem();
        ri.setRequisition(r);
        ri.setMaterialId(item.getMaterialId());
        ri.setBatchId(batch.getId());
        ri.setApplyQty(qty);
        ri.setIssuedQty(qty);
        ri.setIssuePrice(batch.getUnitPrice());
        r.getItems().add(ri);
        reqRepo.save(r);

        // 补录部分冲减账面差异（真实发料，批次已领量增加）
        batch.setQtyIssued(batch.getQtyIssued() + qty);
        batchRepo.save(batch);

        item.setBackfillQty(item.getBackfillQty() + qty);
        item.setBackfillRequisitionId(r.getId());
        item.setResponsibility("CREW");

        // 触发班组领料审批限制：后续领料需项目经理审批
        crew.setRequisitionRestricted(true);
        crew.setRestrictReason("盘点单 " + sc.getCode() + " 补录未登记领料 " + MasterDataService.round(qty)
                + "，后续领料需项目经理审批");
        crewRepo.save(crew);

        checkRepo.save(sc);
        return view(sc);
    }

    /** 追查结论二：无法定位责任，差异转入项目公共损耗（损耗台账留痕，关闭时统一调账） */
    @Transactional
    public Map<String, Object> commonLoss(Long id, Long itemId, CommonLossRequest req) {
        StockCheck sc = checkRepo.findByIdWithItems(id)
                .orElseThrow(() -> new BusinessException("盘点单不存在"));
        StockCheckItem item = lossItem(sc, itemId);
        double qty = req.qty() == null ? 0d : req.qty();
        if (qty <= 0) {
            throw new BusinessException("转公共损耗数量必须大于 0");
        }
        if (qty > item.remainingQty() + 1e-9) {
            throw new BusinessException("转公共损耗数量超过待处理差异，剩余 "
                    + MasterDataService.round(item.remainingQty()));
        }
        WasteRecord w = new WasteRecord();
        w.setCode(codes.next("SH"));
        w.setMaterialId(item.getMaterialId());
        w.setBatchId(item.getBatchId()); // 仅留痕关联批次；库存在盘点关闭调账时统一核销，避免重复扣减
        w.setCrewId(null); // 项目公共损耗，不归集到任何班组
        w.setBuilding(req.building());
        w.setQty(qty);
        w.setUnitPrice(item.getUnitPrice());
        w.setWasteType("OTHER");
        w.setResponsibility("COMPANY");
        w.setDescription("盘点差异无法定位责任，转入项目公共损耗（盘点单 " + sc.getCode() + "）"
                + (req.remark() == null || req.remark().isBlank() ? "" : "；" + req.remark()));
        w.setRecorder(SecurityUtil.currentName());
        w.setCreatedTime(LocalDateTime.now());
        wasteRepo.save(w);

        item.setCommonLossQty(item.getCommonLossQty() + qty);
        item.setResponsibility("COMPANY");
        checkRepo.save(sc);
        return view(sc);
    }

    /** 项目经理确认盘点差异（限制领用）对现场施工进度的影响 */
    @Transactional
    public Map<String, Object> confirmImpact(Long id, ImpactRequest req) {
        StockCheck sc = checkRepo.findByIdWithItems(id)
                .orElseThrow(() -> new BusinessException("盘点单不存在"));
        if (!"COUNTING".equals(sc.getStatus())) {
            throw new BusinessException("仅盘点中单据可确认进度影响");
        }
        if (!Set.of("NONE", "MINOR", "MAJOR").contains(req.impactLevel())) {
            throw new BusinessException("影响程度非法: " + req.impactLevel());
        }
        sc.setImpactLevel(req.impactLevel());
        sc.setImpactNote(req.impactNote());
        sc.setImpactConfirmer(SecurityUtil.currentName());
        sc.setImpactConfirmTime(LocalDateTime.now());
        return view(checkRepo.save(sc));
    }

    /** 关闭盘点：盘亏差异须全部追查处理（补录/公共损耗）且项目经理已确认进度影响 */
    @Transactional
    public Map<String, Object> close(Long id) {
        StockCheck sc = checkRepo.findByIdWithItems(id)
                .orElseThrow(() -> new BusinessException("盘点单不存在"));
        if (!"COUNTING".equals(sc.getStatus())) {
            throw new BusinessException("仅盘点中单据可关闭，当前状态: " + sc.getStatus());
        }
        if (sc.hasLoss()) {
            for (StockCheckItem item : sc.getItems()) {
                if (item.remainingQty() > 1e-9) {
                    throw new BusinessException("材料 " + item.getMaterialId() + " 仍有 "
                            + MasterDataService.round(item.remainingQty())
                            + " 的盘亏差异未处理（补录领料或转公共损耗）");
                }
            }
            if (sc.getImpactConfirmer() == null) {
                throw new BusinessException("项目经理尚未确认盘点差异对施工进度的影响，不能关闭");
            }
        }
        for (StockCheckItem item : sc.getItems()) {
            MaterialBatch b = batchRepo.findById(item.getBatchId())
                    .orElseThrow(() -> new BusinessException("批次不存在"));
            // 补录已真实冲减账面，此处仅调整剩余差异，使账面 = 实盘
            double diff = item.getActualQty() - b.onHand();
            b.setQtyAdjust(b.getQtyAdjust() + diff);
            batchRepo.save(b);
        }
        sc.setStatus("CLOSED");
        sc.setConfirmer(SecurityUtil.currentName());
        sc.setAdjustTime(LocalDateTime.now());
        return view(checkRepo.save(sc));
    }

    /** 未关闭盘点差异涉及的材料（materialId -> 盘点单号），差异关闭前限制继续领用 */
    public Map<Long, String> restrictedMaterials() {
        Map<Long, String> out = new HashMap<>();
        for (Object[] row : checkRepo.findOpenLossMaterials()) {
            out.put((Long) row[0], (String) row[1]);
        }
        return out;
    }

    public List<Map<String, Object>> list() {
        return checkRepo.findAllWithItems().stream().map(this::view).toList();
    }

    public Map<String, Object> view(StockCheck sc) {
        Map<Long, Material> mats = materialRepo.findAll().stream()
                .collect(Collectors.toMap(Material::getId, x -> x));
        List<Map<String, Object>> items = sc.getItems().stream().map(i -> {
            Material m = mats.get(i.getMaterialId());
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("itemId", i.getId());
            x.put("materialId", i.getMaterialId());
            x.put("materialName", m == null ? null : m.getName());
            x.put("unit", m == null ? null : m.getUnit());
            x.put("batchId", i.getBatchId());
            x.put("bookQty", i.getBookQty());
            x.put("actualQty", i.getActualQty());
            x.put("diffQty", MasterDataService.round(i.diffQty()));
            x.put("lossQty", MasterDataService.round(i.lossQty()));
            x.put("backfillQty", MasterDataService.round(i.getBackfillQty()));
            x.put("commonLossQty", MasterDataService.round(i.getCommonLossQty()));
            x.put("remainingQty", MasterDataService.round(i.remainingQty()));
            x.put("unitPrice", i.getUnitPrice());
            x.put("diffAmount", MasterDataService.round(i.diffQty() * i.getUnitPrice()));
            x.put("responsibility", i.getResponsibility());
            x.put("backfillRequisitionId", i.getBackfillRequisitionId());
            x.put("remark", i.getRemark());
            return x;
        }).toList();
        boolean lossResolved = sc.getItems().stream().allMatch(i -> i.remainingQty() <= 1e-9);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", sc.getId());
        m.put("code", sc.getCode());
        m.put("scope", sc.getScope());
        m.put("status", sc.getStatus());
        m.put("recorder", sc.getRecorder());
        m.put("confirmer", sc.getConfirmer());
        m.put("createdTime", sc.getCreatedTime());
        m.put("adjustTime", sc.getAdjustTime());
        m.put("remark", sc.getRemark());
        m.put("impactLevel", sc.getImpactLevel());
        m.put("impactNote", sc.getImpactNote());
        m.put("impactConfirmer", sc.getImpactConfirmer());
        m.put("impactConfirmTime", sc.getImpactConfirmTime());
        m.put("hasLoss", sc.hasLoss());
        m.put("lossResolved", lossResolved);
        m.put("canClose", "COUNTING".equals(sc.getStatus())
                && lossResolved && (!sc.hasLoss() || sc.getImpactConfirmer() != null));
        m.put("items", items);
        m.put("diffTotal", MasterDataService.round(items.stream()
                .mapToDouble(x -> (double) x.get("diffAmount")).sum()));
        return m;
    }

    private StockCheckItem lossItem(StockCheck sc, Long itemId) {
        if (!"COUNTING".equals(sc.getStatus())) {
            throw new BusinessException("仅盘点中单据可处理差异，当前状态: " + sc.getStatus());
        }
        StockCheckItem item = sc.getItems().stream()
                .filter(i -> i.getId().equals(itemId)).findFirst()
                .orElseThrow(() -> new BusinessException("盘点明细不存在"));
        if (item.lossQty() <= 1e-9) {
            throw new BusinessException("该明细无盘亏差异，无需处理");
        }
        return item;
    }

    private Map<String, Object> handoverView(HandoverRecord h, Map<Long, String> crewNames,
                                             Map<Long, Material> mats) {
        Map<String, Object> x = new LinkedHashMap<>();
        x.put("id", h.getId());
        x.put("handoverTime", h.getHandoverTime());
        x.put("fromKeeper", h.getFromKeeper());
        x.put("toKeeper", h.getToKeeper());
        x.put("cctvRef", h.getCctvRef());
        x.put("note", h.getNote());
        x.put("relatedCrewId", h.getRelatedCrewId());
        x.put("relatedCrewName", h.getRelatedCrewId() == null ? null : crewNames.get(h.getRelatedCrewId()));
        x.put("relatedMaterialId", h.getRelatedMaterialId());
        Material m = h.getRelatedMaterialId() == null ? null : mats.get(h.getRelatedMaterialId());
        x.put("relatedMaterialName", m == null ? null : m.getName());
        x.put("recorder", h.getRecorder());
        return x;
    }
}
