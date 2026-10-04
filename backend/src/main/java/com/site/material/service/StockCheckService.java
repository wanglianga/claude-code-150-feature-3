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
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 月底盘点账实差异追查闭环：
 *  1. 登记盘点（账实不符的材料在差异关闭前冻结领用）；
 *  2. 按 入库 / 领用 / 退库 / 破损登记 / 监控交接 五条线核查并留痕；
 *  3. 定性处理：
 *     - 无法定位责任 -> 项目公共损耗（生成 COMPANY 损耗单、核销批次、进公共损耗台账）；
 *     - 发现班组未登记夜间领料 -> 补录领料单（回写批次已发量、计入班组成本、冻结该班组后续领料须 PM 审批）；
 *  4. 项目经理确认是否影响现场施工进度；
 *  5. 全部盘亏轧平后关闭盘点单，解除材料领用冻结（尾差以批次盘点调整量收口）。
 */
@Service
public class StockCheckService {

    public static final Set<String> TRACE_TYPES =
            Set.of("INBOUND", "REQUISITION", "RETURN", "WASTE", "MONITOR");
    private static final Set<String> TRACE_RESULTS = Set.of("MATCHED", "SUSPECT", "CONFIRMED", "INFO");
    private static final DateTimeFormatter DT_MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private final StockCheckRepository checkRepo;
    private final StockCheckTraceRepository traceRepo;
    private final MaterialBatchRepository batchRepo;
    private final MaterialRepository materialRepo;
    private final CrewRepository crewRepo;
    private final RequisitionRepository reqRepo;
    private final WasteRepository wasteRepo;
    private final CodeGenerator codes;

    public StockCheckService(StockCheckRepository checkRepo, StockCheckTraceRepository traceRepo,
                             MaterialBatchRepository batchRepo, MaterialRepository materialRepo,
                             CrewRepository crewRepo, RequisitionRepository reqRepo,
                             WasteRepository wasteRepo, CodeGenerator codes) {
        this.checkRepo = checkRepo;
        this.traceRepo = traceRepo;
        this.batchRepo = batchRepo;
        this.materialRepo = materialRepo;
        this.crewRepo = crewRepo;
        this.reqRepo = reqRepo;
        this.wasteRepo = wasteRepo;
        this.codes = codes;
    }

    // ---------------- 建单 ----------------
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
        boolean anyShortage = false;
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
            if (item.shortageQty() > 1e-6) anyShortage = true;
            sc.getItems().add(item);
        }
        StockCheck saved = checkRepo.save(sc);
        trace(saved, "INFO", "INFO", null, null, null,
                (anyShortage ? "盘点发现账实不符，盘亏材料自即日起冻结领用，按入库/领用/退库/破损/监控交接追查。"
                        : "盘点账实相符，留档备查。") + (req.remark() == null ? "" : " 备注：" + req.remark()));
        return saved;
    }

    // ---------------- 追查轨迹 ----------------
    @Transactional
    public StockCheck addTrace(Long checkId, TraceRequest req) {
        StockCheck sc = mustBeCounting(checkId);
        if (!TRACE_TYPES.contains(req.traceType())) {
            throw new BusinessException("核查方向非法: " + req.traceType());
        }
        String result = req.result() == null ? "INFO" : req.result();
        if (!TRACE_RESULTS.contains(result)) {
            throw new BusinessException("核查结论非法: " + result);
        }
        return trace(sc, req.traceType(), result, req.materialId(), req.refCode(), req.qty(), req.detail());
    }

    private StockCheck trace(StockCheck sc, String type, String result, Long materialId,
                             String refCode, Double qty, String detail) {
        StockCheckTrace t = new StockCheckTrace();
        t.setStockCheck(sc);
        t.setTraceType(type);
        t.setResult(result);
        t.setMaterialId(materialId);
        t.setRefCode(refCode);
        t.setQty(qty);
        t.setDetail(detail);
        t.setRecorder(SecurityUtil.currentName());
        t.setCreatedTime(LocalDateTime.now());
        traceRepo.save(t);
        sc.getItems().size(); // OSIV 关闭：事务内初始化懒加载集合，供控制器序列化
        return sc;
    }

    // ---------------- 定性：项目公共损耗 ----------------
    @Transactional
    public Map<String, Object> resolveCommonLoss(Long checkId, CommonLossRequest req) {
        StockCheck sc = mustBeCounting(checkId);
        StockCheckItem item = mustFindItem(sc, req.itemId());
        double qty = req.qty();
        if (qty <= 0) throw new BusinessException("公共损耗数量必须大于 0");
        if (qty > item.unexplainedQty() + 1e-6) {
            throw new BusinessException("公共损耗数量超过未解释盘亏量："
                    + MasterDataService.round(item.unexplainedQty()));
        }
        Material m = materialRepo.findById(item.getMaterialId())
                .orElseThrow(() -> new BusinessException("材料不存在"));
        MaterialBatch batch = batchRepo.findById(item.getBatchId())
                .orElseThrow(() -> new BusinessException("批次不存在"));
        if (batch.onHand() + 1e-9 < qty) {
            throw new BusinessException("批次现场库存不足，无法核销公共损耗");
        }

        WasteRecord w = new WasteRecord();
        w.setCode(codes.next("SH"));
        w.setMaterialId(item.getMaterialId());
        w.setBatchId(batch.getId());
        w.setCrewId(null); // 无法定位责任 -> 项目公共损耗，不扣班组
        w.setBuilding(sc.getScope() == null ? "现场仓库" : sc.getScope());
        w.setArea("盘点差异");
        w.setProcess("月底盘点");
        w.setQty(qty);
        w.setUnitPrice(item.getUnitPrice());
        w.setWasteType("OTHER");
        w.setResponsibility("COMPANY");
        w.setDescription("盘点单 " + sc.getCode() + " 经入库/领用/退库/破损/监控交接核查无法定位责任，"
                + "差异计入项目公共损耗。" + (req.remark() == null ? "" : req.remark()));
        w.setRecorder(SecurityUtil.currentName());
        w.setCreatedTime(LocalDateTime.now());
        wasteRepo.save(w);

        // 核销批次库存（与损耗登记同一口径）
        batch.setQtyWasted(batch.getQtyWasted() + qty);

        item.setCommonLossQty(item.getCommonLossQty() + qty);
        item.setCommonWasteId(w.getId());
        item.setResponsibility("COMPANY");
        refreshItemResolution(item);
        checkRepo.save(sc);
        trace(sc, "COMMON_LOSS", "CONFIRMED", item.getMaterialId(), w.getCode(), qty,
                m.getName() + " 盘亏 " + qty + m.getUnit() + " 无法定位责任，计入项目公共损耗（损耗单 " + w.getCode() + "）");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("wasteCode", w.getCode());
        out.put("check", view(sc));
        return out;
    }

    // ---------------- 定性：补录班组夜间未登记领料 ----------------
    @Transactional
    public Map<String, Object> supplementNight(Long checkId, SupplementNightRequest req) {
        StockCheck sc = mustBeCounting(checkId);
        StockCheckItem item = mustFindItem(sc, req.itemId());
        double qty = req.qty();
        if (qty <= 0) throw new BusinessException("补录数量必须大于 0");
        if (qty > item.unexplainedQty() + 1e-6) {
            throw new BusinessException("补录数量超过未解释盘亏量："
                    + MasterDataService.round(item.unexplainedQty()));
        }
        Crew crew = crewRepo.findById(req.crewId())
                .orElseThrow(() -> new BusinessException("责任班组不存在"));
        Material m = materialRepo.findById(item.getMaterialId())
                .orElseThrow(() -> new BusinessException("材料不存在"));
        Long batchId = req.batchId() != null ? req.batchId() : item.getBatchId();
        MaterialBatch batch = batchRepo.findById(batchId)
                .orElseThrow(() -> new BusinessException("批次不存在"));
        if (!batch.getMaterialId().equals(item.getMaterialId())) {
            throw new BusinessException("批次材料与盘点项不一致");
        }
        if ("FAILED".equals(batch.getQcStatus()) || "PENDING".equals(batch.getQcStatus())) {
            throw new BusinessException("批次 " + batch.getBatchNo() + " 质检状态不可发料");
        }
        if (batch.onHand() + 1e-9 < qty) {
            throw new BusinessException("批次现场库存不足，无法补录：账面 "
                    + MasterDataService.round(batch.onHand()));
        }

        LocalDateTime actualTime = parseTime(req.actualTime());
        String operator = SecurityUtil.currentName();

        Requisition r = new Requisition();
        r.setCode(codes.next("BL"));
        r.setCrewId(crew.getId());
        r.setBuilding(req.building());
        r.setArea(req.area());
        r.setProcess(req.process());
        r.setDrawingNo(req.drawingNo());
        r.setUrgent(true);
        r.setBackdated(true);
        r.setSourceCheckId(sc.getId());
        r.setApplicant(crew.getLeader() == null ? crew.getName() : crew.getLeader());
        r.setConfirmedBy(crew.getLeader());
        r.setIssuedBy(operator);
        r.setApplyTime(actualTime);
        r.setActualUseTime(actualTime);
        // 先发后补：实物发出时间以夜间实际领料时间为准，补录操作人记为仓管
        r.setIssueTime(actualTime);
        r.setStatus("ISSUED");
        r.setRemark("盘点单 " + sc.getCode() + " 追查确认：夜间未登记领料，由仓管补录，计入班组成本；"
                + "自补录之日起该班组后续领料须项目经理逐单审批。"
                + (req.remark() == null ? "" : " " + req.remark()));
        RequisitionItem ri = new RequisitionItem();
        ri.setRequisition(r);
        ri.setMaterialId(item.getMaterialId());
        ri.setBatchId(batch.getId());
        ri.setApplyQty(qty);
        ri.setIssuedQty(qty);
        ri.setIssuePrice(batch.getUnitPrice());
        r.getItems().add(ri);
        reqRepo.save(r);

        // 回写批次已发量（解释盘亏：实物早已被班组领用）
        batch.setQtyIssued(batch.getQtyIssued() + qty);

        item.setSupplementedQty(item.getSupplementedQty() + qty);
        item.setResponsibleCrewId(crew.getId());
        item.setSupplementedReqId(r.getId());
        item.setResponsibility("CREW");
        refreshItemResolution(item);
        checkRepo.save(sc);

        // 加严该班组后续领料审批：新单一律转 PM 审批
        if (!crew.isRequisitionRestricted()) {
            crew.setRequisitionRestricted(true);
            crew.setRestrictReason("盘点单 " + sc.getCode() + " 发现未登记夜间领料 "
                    + m.getName() + " " + qty + m.getUnit());
            crew.setRestrictedBy(operator);
            crew.setRestrictedTime(LocalDateTime.now());
            crewRepo.save(crew);
        }

        trace(sc, "SUPPLEMENT", "CONFIRMED", item.getMaterialId(), r.getCode(), qty,
                "监控交接与班组确认：" + crew.getName() + " 于 " + actualTime.format(DT_MINUTE)
                        + " 夜间领用 " + m.getName() + " " + qty + m.getUnit() + " 未办手续，已补录 "
                        + r.getCode() + "（金额 " + MasterDataService.round(qty * batch.getUnitPrice())
                        + " 元计入班组成本），后续领料转 PM 审批。");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requisitionCode", r.getCode());
        out.put("crewRestricted", true);
        out.put("check", view(sc));
        return out;
    }

    // ---------------- 项目经理确认进度影响 ----------------
    @Transactional
    public StockCheck confirmProgress(Long checkId, ProgressConfirmRequest req) {
        StockCheck sc = mustBeCounting(checkId);
        if (!"YES".equals(req.impact()) && !"NO".equals(req.impact())) {
            throw new BusinessException("进度影响取值须为 YES / NO");
        }
        sc.setProgressImpact(req.impact());
        sc.setProgressConfirmedBy(SecurityUtil.currentName());
        sc.setProgressConfirmTime(LocalDateTime.now());
        sc.setProgressRemark(req.remark());
        checkRepo.save(sc);
        trace(sc, "PROGRESS", "CONFIRMED", null, null, null,
                "项目经理确认：盘点差异" + ("YES".equals(req.impact()) ? "将影响" : "不影响")
                        + "现场施工进度。" + (req.remark() == null ? "" : " 说明：" + req.remark()));
        sc.getItems().size();
        return sc;
    }

    // ---------------- 关闭盘点 ----------------
    @Transactional
    public StockCheck close(Long checkId) {
        StockCheck sc = checkRepo.findById(checkId)
                .orElseThrow(() -> new BusinessException("盘点单不存在"));
        if (!"COUNTING".equals(sc.getStatus())) {
            throw new BusinessException("追查中的盘点单才可关闭，当前状态: " + sc.getStatus());
        }
        List<String> open = new ArrayList<>();
        for (StockCheckItem item : sc.getItems()) {
            if (item.shortageQty() > 1e-6 && item.unexplainedQty() > 1e-6) {
                Material m = materialRepo.findById(item.getMaterialId()).orElse(null);
                open.add((m == null ? ("材料#" + item.getMaterialId()) : m.getName())
                        + " 未解释盘亏 " + MasterDataService.round(item.unexplainedQty()));
            }
        }
        if (!open.isEmpty()) {
            throw new BusinessException("仍有盘亏未定性，不能关闭：" + String.join("；", open));
        }
        if (sc.getProgressImpact() == null) {
            throw new BusinessException("关闭前须由项目经理确认差异是否影响现场施工进度");
        }

        // 尾差收口：逐项把批次账面调到实盘（覆盖盘盈及四舍五入尾差）
        for (StockCheckItem item : sc.getItems()) {
            MaterialBatch b = batchRepo.findById(item.getBatchId()).orElse(null);
            if (b == null) continue;
            double residual = item.getActualQty() - b.onHand();
            if (Math.abs(residual) > 1e-6) {
                b.setQtyAdjust(b.getQtyAdjust() + residual);
            }
        }

        boolean hasCommon = sc.getItems().stream().anyMatch(i -> i.getCommonLossQty() > 1e-9);
        boolean hasSuppl = sc.getItems().stream().anyMatch(i -> i.getSupplementedQty() > 1e-9);
        sc.setConclusion(hasCommon && hasSuppl ? "MIXED" : hasSuppl ? "CREW_NIGHT" : "COMMON_LOSS");
        sc.setStatus("CLOSED");
        sc.setClosedBy(SecurityUtil.currentName());
        sc.setConfirmer(SecurityUtil.currentName());
        sc.setCloseTime(LocalDateTime.now());
        sc.setAdjustTime(LocalDateTime.now());
        checkRepo.save(sc);
        trace(sc, "CLOSE", "CONFIRMED", null, null, null,
                "全部盘亏已定性轧平，进度影响已确认，盘点单关闭，解除差异材料领用冻结。");
        return sc;
    }

    /** 盘点追查中：存在盘亏且未关闭的材料 id —— 这些材料冻结继续领用 */
    public Set<Long> frozenMaterialIds() {
        Set<Long> ids = new HashSet<>();
        for (StockCheck sc : checkRepo.findAllWithItems()) {
            if (!"COUNTING".equals(sc.getStatus())) continue;
            for (StockCheckItem i : sc.getItems()) {
                if (i.shortageQty() > 1e-9) ids.add(i.getMaterialId());
            }
        }
        return ids;
    }

    public Map<Long, String> frozenCheckCodes() {
        Map<Long, String> map = new HashMap<>();
        for (StockCheck sc : checkRepo.findAllWithItems()) {
            if (!"COUNTING".equals(sc.getStatus())) continue;
            for (StockCheckItem i : sc.getItems()) {
                if (i.shortageQty() > 1e-9) map.putIfAbsent(i.getMaterialId(), sc.getCode());
            }
        }
        return map;
    }

    // ---------------- 查询视图 ----------------
    public List<Map<String, Object>> list() {
        Map<Long, String> matNames = materialRepo.findAll().stream()
                .collect(Collectors.toMap(Material::getId, Material::getName));
        Map<Long, String> units = materialRepo.findAll().stream()
                .collect(Collectors.toMap(Material::getId, Material::getUnit));
        Map<Long, String> crewNames = crewRepo.findAll().stream()
                .collect(Collectors.toMap(Crew::getId, Crew::getName));
        Map<Long, String> reqCodes = reqRepo.findAll().stream()
                .collect(Collectors.toMap(Requisition::getId, Requisition::getCode));
        Map<Long, String> wasteCodes = wasteRepo.findAll().stream()
                .collect(Collectors.toMap(WasteRecord::getId, WasteRecord::getCode));

        return checkRepo.findAllWithItems().stream()
                .sorted(Comparator.comparing(StockCheck::getCreatedTime,
                        Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .map(sc -> toView(sc, matNames, units, crewNames, reqCodes, wasteCodes))
                .toList();
    }

    private Map<String, Object> view(StockCheck sc) {
        return list().stream().filter(x -> x.get("id").equals(sc.getId())).findFirst()
                .orElseGet(LinkedHashMap::new);
    }

    private Map<String, Object> toView(StockCheck sc, Map<Long, String> matNames, Map<Long, String> units,
                                       Map<Long, String> crewNames, Map<Long, String> reqCodes,
                                       Map<Long, String> wasteCodes) {
        double shortageTotal = 0, supplementedTotal = 0, commonTotal = 0, unexplainedTotal = 0;
        List<Long> crewIds = new ArrayList<>();
        List<Map<String, Object>> items = new ArrayList<>();
        for (StockCheckItem i : sc.getItems()) {
            double shortage = i.shortageQty();
            shortageTotal += shortage;
            supplementedTotal += i.getSupplementedQty();
            commonTotal += i.getCommonLossQty();
            unexplainedTotal += Math.max(0d, i.unexplainedQty());
            if (i.getResponsibleCrewId() != null && !crewIds.contains(i.getResponsibleCrewId())) {
                crewIds.add(i.getResponsibleCrewId());
            }
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("itemId", i.getId());
            x.put("materialId", i.getMaterialId());
            x.put("materialName", matNames.get(i.getMaterialId()));
            x.put("unit", units.get(i.getMaterialId()));
            x.put("batchId", i.getBatchId());
            x.put("bookQty", i.getBookQty());
            x.put("actualQty", i.getActualQty());
            x.put("diffQty", MasterDataService.round(i.diffQty()));
            x.put("shortageQty", MasterDataService.round(shortage));
            x.put("unitPrice", i.getUnitPrice());
            x.put("diffAmount", MasterDataService.round(i.diffQty() * i.getUnitPrice()));
            x.put("responsibility", i.getResponsibility());
            x.put("resolution", i.getResolution());
            x.put("supplementedQty", MasterDataService.round(i.getSupplementedQty()));
            x.put("commonLossQty", MasterDataService.round(i.getCommonLossQty()));
            x.put("unexplainedQty", MasterDataService.round(Math.max(0d, i.unexplainedQty())));
            x.put("responsibleCrewId", i.getResponsibleCrewId());
            x.put("responsibleCrewName", i.getResponsibleCrewId() == null ? null
                    : crewNames.get(i.getResponsibleCrewId()));
            x.put("supplementedReqCode", i.getSupplementedReqId() == null ? null
                    : reqCodes.get(i.getSupplementedReqId()));
            x.put("commonWasteCode", i.getCommonWasteId() == null ? null
                    : wasteCodes.get(i.getCommonWasteId()));
            x.put("remark", i.getRemark());
            items.add(x);
        }

        List<Map<String, Object>> traces = traceRepo.findByStockCheckOrderByCreatedTimeAscIdAsc(sc).stream()
                .map(t -> {
                    Map<String, Object> x = new LinkedHashMap<>();
                    x.put("id", t.getId());
                    x.put("traceType", t.getTraceType());
                    x.put("result", t.getResult());
                    x.put("materialName", t.getMaterialId() == null ? null : matNames.get(t.getMaterialId()));
                    x.put("refCode", t.getRefCode());
                    x.put("qty", t.getQty());
                    x.put("detail", t.getDetail());
                    x.put("recorder", t.getRecorder());
                    x.put("createdTime", t.getCreatedTime());
                    return x;
                }).toList();

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", sc.getId());
        m.put("code", sc.getCode());
        m.put("scope", sc.getScope());
        m.put("status", sc.getStatus());
        m.put("conclusion", sc.getConclusion());
        m.put("recorder", sc.getRecorder());
        m.put("confirmer", sc.getConfirmer());
        m.put("createdTime", sc.getCreatedTime());
        m.put("adjustTime", sc.getAdjustTime());
        m.put("closeTime", sc.getCloseTime());
        m.put("closedBy", sc.getClosedBy());
        m.put("remark", sc.getRemark());
        m.put("progressImpact", sc.getProgressImpact());
        m.put("progressConfirmedBy", sc.getProgressConfirmedBy());
        m.put("progressConfirmTime", sc.getProgressConfirmTime());
        m.put("progressRemark", sc.getProgressRemark());
        m.put("items", items);
        m.put("traces", traces);
        m.put("shortageTotal", MasterDataService.round(shortageTotal));
        m.put("shortageAmount", MasterDataService.round(items.stream()
                .mapToDouble(x -> (double) x.get("shortageQty") * (double) x.get("unitPrice")).sum()));
        m.put("supplementedTotal", MasterDataService.round(supplementedTotal));
        m.put("commonLossTotal", MasterDataService.round(commonTotal));
        m.put("unexplainedTotal", MasterDataService.round(unexplainedTotal));
        m.put("frozen", "COUNTING".equals(sc.getStatus()) && shortageTotal > 1e-9);
        m.put("restrictedCrewIds", crewIds);
        m.put("canClose", "COUNTING".equals(sc.getStatus())
                && unexplainedTotal <= 1e-6 && sc.getProgressImpact() != null);
        return m;
    }

    // ---------------- helpers ----------------
    private StockCheck mustBeCounting(Long id) {
        StockCheck sc = checkRepo.findById(id)
                .orElseThrow(() -> new BusinessException("盘点单不存在"));
        if (!"COUNTING".equals(sc.getStatus())) {
            throw new BusinessException("盘点单已关闭，不能再追查或定性");
        }
        return sc;
    }

    private StockCheckItem mustFindItem(StockCheck sc, Long itemId) {
        return sc.getItems().stream().filter(i -> i.getId().equals(itemId)).findFirst()
                .orElseThrow(() -> new BusinessException("盘点明细不存在"));
    }

    private void refreshItemResolution(StockCheckItem item) {
        boolean sup = item.getSupplementedQty() > 1e-9;
        boolean com = item.getCommonLossQty() > 1e-9;
        if (sup && com) item.setResolution("MIXED");
        else if (sup) item.setResolution("SUPPLEMENTED");
        else if (com) item.setResolution("COMMON_LOSS");
        else item.setResolution("OPEN");
    }

    private LocalDateTime parseTime(String s) {
        if (s == null || s.isBlank()) return LocalDateTime.now();
        try {
            return LocalDateTime.parse(s);
        } catch (Exception ignore) {
            try {
                return LocalDateTime.parse(s, DT_MINUTE);
            } catch (Exception e) {
                throw new BusinessException("夜间领料时间格式应为 yyyy-MM-ddTHH:mm");
            }
        }
    }
}
