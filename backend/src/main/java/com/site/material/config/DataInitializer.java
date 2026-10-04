package com.site.material.config;

import com.site.material.domain.*;
import com.site.material.repo.*;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 演示种子数据：覆盖入库质检、预算、领料（含夜间紧急）、退库（规格领错/变更/损坏）、
 * 损耗（瓷砖破损/砂浆过期/供应商质量/设计变更）、变更签证、盘点账实不符全链路。
 */
@Component
public class DataInitializer implements CommandLineRunner {

    private final UserRepository userRepo;
    private final CrewRepository crewRepo;
    private final SupplierRepository supplierRepo;
    private final MaterialRepository materialRepo;
    private final MaterialBatchRepository batchRepo;
    private final ProjectBudgetRepository budgetRepo;
    private final RequisitionRepository reqRepo;
    private final MaterialReturnRepository returnRepo;
    private final WasteRepository wasteRepo;
    private final ChangeVisaRepository visaRepo;
    private final StockCheckRepository checkRepo;
    private final StockCheckTraceRepository traceRepo;

    public DataInitializer(UserRepository userRepo, CrewRepository crewRepo, SupplierRepository supplierRepo,
                           MaterialRepository materialRepo, MaterialBatchRepository batchRepo,
                           ProjectBudgetRepository budgetRepo, RequisitionRepository reqRepo,
                           MaterialReturnRepository returnRepo, WasteRepository wasteRepo,
                           ChangeVisaRepository visaRepo, StockCheckRepository checkRepo,
                           StockCheckTraceRepository traceRepo) {
        this.userRepo = userRepo;
        this.crewRepo = crewRepo;
        this.supplierRepo = supplierRepo;
        this.materialRepo = materialRepo;
        this.batchRepo = batchRepo;
        this.budgetRepo = budgetRepo;
        this.reqRepo = reqRepo;
        this.returnRepo = returnRepo;
        this.wasteRepo = wasteRepo;
        this.visaRepo = visaRepo;
        this.checkRepo = checkRepo;
        this.traceRepo = traceRepo;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (userRepo.count() > 0) return;

        // ---------- 班组 ----------
        Crew c1 = crew("钢筋班", "王强", "钢筋");
        Crew c2 = crew("泥工班", "李勇", "泥工");
        Crew c3 = crew("瓷砖班", "陈海", "镶贴");
        Crew c4 = crew("防水班", "赵磊", "防水");
        Crew c5 = crew("水电班", "孙明", "水电");
        crewRepo.save(c1); crewRepo.save(c2); crewRepo.save(c3); crewRepo.save(c4); crewRepo.save(c5);

        // ---------- 用户（演示密码均为明文校验） ----------
        user("pm", "pm123", "张项目", "ROLE_PM", null);
        user("keeper", "keeper123", "周材料", "ROLE_KEEPER", null);
        user("budget", "budget123", "吴预算", "ROLE_BUDGET", null);
        user("finance", "finance123", "郑财务", "ROLE_FINANCE", null);
        user("foreman1", "foreman123", "王强", "ROLE_FOREMAN", c1.getId());
        user("foreman2", "foreman123", "李勇", "ROLE_FOREMAN", c2.getId());
        user("foreman3", "foreman123", "陈海", "ROLE_FOREMAN", c3.getId());
        user("foreman4", "foreman123", "赵磊", "ROLE_FOREMAN", c4.getId());
        user("foreman5", "foreman123", "孙明", "ROLE_FOREMAN", c5.getId());

        // ---------- 供应商 ----------
        Supplier s1 = supplier("敬业钢铁有限公司", "刘经理", "13800000001");
        Supplier s2 = supplier("雨虹建材有限公司", "马经理", "13800000002");
        Supplier s3 = supplier("马可波罗建材商行", "徐经理", "13800000003");
        Supplier s4 = supplier("海螺新型材料公司", "何经理", "13800000004");
        Supplier s5 = supplier("联塑管业有限公司", "高经理", "13800000005");
        Supplier s6 = supplier("鼎力五金机电", "林店长", "13800000006");
        Supplier s7 = supplier("安泰劳动防护用品", "潘经理", "13800000007");
        supplierRepo.save(s1); supplierRepo.save(s2); supplierRepo.save(s3);
        supplierRepo.save(s4); supplierRepo.save(s5); supplierRepo.save(s6); supplierRepo.save(s7);

        // ---------- 材料 ----------
        Material m1 = material("螺纹钢 HRB400 Φ20", "钢筋辅材", "Φ20", "吨", 5d);
        Material m2 = material("绑扎铁丝", "钢筋辅材", "22#", "卷", 30d);
        Material m3 = material("SBS改性沥青防水卷材", "防水卷材", "4mm", "㎡", 200d);
        Material m4 = material("防滑地砖", "瓷砖", "600×600", "箱", 50d);
        Material m5 = material("干混砌筑砂浆", "砂浆", "M5", "吨", 10d);
        Material m6 = material("PVC穿线管", "线管", "DN20", "米", 300d);
        Material m7 = material("膨胀螺栓", "五金", "M10×80", "套", 500d);
        Material m8 = material("安全帽", "安全防护用品", "V型ABS", "顶", 20d);
        Material m9 = material("密目安全网", "安全防护用品", "1.8×6m", "张", 20d);

        // ---------- 入库批次 ----------
        MaterialBatch b1 = batch("RK20260928001", m1, "Φ20", s1, 50, 4200, "PASSED",
                12, 0, 0, 0, t(9, 28, 9));
        MaterialBatch b2 = batch("RK20260928002", m2, "22#", s1, 200, 25, "PASSED",
                60, 0, 0, 0, t(9, 28, 9));
        MaterialBatch b3 = batch("RK20260927001", m3, "4mm", s2, 2000, 32, "PASSED",
                300, 20, 10, 0, t(9, 27, 10));
        MaterialBatch b3b = batch("RK20261002001", m3, "4mm(补货)", s2, 200, 32, "FAILED",
                0, 0, 30, 0, t(10, 2, 15));
        MaterialBatch b4 = batch("RK20260929001", m4, "600×600", s3, 1000, 58, "PASSED",
                120, 0, 5, 0, t(9, 29, 8));
        MaterialBatch b5 = batch("RK20260926001", m5, "M5", s4, 80, 380, "PASSED",
                20, 0, 0.5, 0, t(9, 26, 14));
        MaterialBatch b6 = batch("RK20260925001", m6, "DN20", s5, 3000, 3.5, "PASSED",
                400, 50, 0, 0, t(9, 25, 11));
        MaterialBatch b7 = batch("RK20260925002", m7, "M10×80", s6, 5000, 1.2, "PASSED",
                800, 0, 0, 0, t(9, 25, 11));
        MaterialBatch b8 = batch("RK20260920001", m8, "V型ABS", s7, 100, 28, "PASSED",
                10, 0, 0, 0, t(9, 20, 9));
        MaterialBatch b9 = batch("RK20260920002", m9, "1.8×6m", s7, 200, 45, "CONDITIONAL",
                30, 0, 0, 0, t(9, 20, 9));

        // ---------- 预算（楼栋/区域/工序/图纸） ----------
        budget("1#楼", "A区", "主体结构", "结施-101", m1, 20, 4200, c1);
        budget("1#楼", "A区", "主体结构", "结施-101", m2, 150, 25, c1);
        budget("1#楼", "A区", "主体结构", "结施-101", m8, 30, 28, c1);
        budget("1#楼", "地下室", "防水工程", "建防-201", m3, 2600, 32, c4);
        budget("1#楼", "地下室", "防水工程", "建防-201", m9, 100, 45, c4);
        budget("1#楼", "标准层", "墙地砖铺贴", "建装-305", m4, 150, 58, c3);
        budget("1#楼", "标准层", "砌筑抹灰", "建施-301", m5, 60, 380, c2);
        budget("1#楼", "标准层", "机电预埋", "电施-401", m6, 2000, 3.5, c5);
        budget("1#楼", "标准层", "机电预埋", "电施-401", m7, 3000, 1.2, c5);
        budget("2#楼", "A区", "主体结构", "结施-102", m1, 8, 4200, c1);

        // ---------- 已发料领料单（10月） ----------
        Requisition r1 = requisition("LL20261001001", c1, "1#楼", "A区", "主体结构", "结施-101",
                "ISSUED", false, "王强", "周材料", t(10, 1, 7), t(10, 1, 8));
        item(r1, m1, b1, 10, 10, 4200);
        saveReq(r1);

        Requisition r2 = requisition("LL20261001002", c1, "1#楼", "A区", "主体结构", "结施-101",
                "ISSUED", false, "王强", "周材料", t(10, 2, 7), t(10, 2, 8));
        item(r2, m1, b1, 2, 2, 4200);
        item(r2, m2, b2, 60, 60, 25);
        saveReq(r2);

        Requisition r3 = requisition("LL20261001003", c4, "1#楼", "地下室", "防水工程", "建防-201",
                "ISSUED", false, "赵磊", "周材料", t(10, 1, 9), t(10, 1, 10));
        item(r3, m3, b3, 300, 300, 32);
        saveReq(r3);

        Requisition r4 = requisition("LL20261001004", c3, "1#楼", "标准层", "墙地砖铺贴", "建装-305",
                "ISSUED", false, "陈海", "周材料", t(10, 1, 13), t(10, 1, 14));
        item(r4, m4, b4, 120, 120, 58);
        saveReq(r4);

        Requisition r5 = requisition("LL20261001005", c2, "1#楼", "标准层", "砌筑抹灰", "建施-301",
                "ISSUED", false, "李勇", "周材料", t(10, 1, 15), t(10, 1, 16));
        item(r5, m5, b5, 20, 20, 380);
        saveReq(r5);

        Requisition r6 = requisition("LL20261001006", c5, "1#楼", "标准层", "机电预埋", "电施-401",
                "ISSUED", false, "孙明", "周材料", t(10, 1, 10), t(10, 1, 11));
        item(r6, m6, b6, 400, 400, 3.5);
        item(r6, m7, b7, 800, 800, 1.2);
        saveReq(r6);

        Requisition r7 = requisition("LL20261002007", c1, "1#楼", "A区", "主体结构", "结施-101",
                "ISSUED", false, "王强", "周材料", t(10, 2, 9), t(10, 2, 9));
        item(r7, m8, b8, 10, 10, 28);
        saveReq(r7);

        Requisition r8 = requisition("LL20261002008", c4, "1#楼", "地下室", "防水工程", "建防-201",
                "ISSUED", false, "赵磊", "周材料", t(10, 2, 9), t(10, 2, 10));
        item(r8, m9, b9, 30, 30, 45);
        saveReq(r8);

        // ---------- 待发料（含超预算、夜间紧急） ----------
        Requisition p1 = requisition("LL20261003009", c1, "1#楼", "A区", "主体结构", "结施-101",
                "SUBMITTED", false, "王强", null, t(10, 3, 8), null);
        item(p1, m1, null, 3, 0, 0);
        saveReq(p1);

        Requisition p2 = requisition("LL20261003010", c3, "1#楼", "标准层", "墙地砖铺贴", "建装-305",
                "SUBMITTED", false, "陈海", null, t(10, 3, 8), null);
        item(p2, m4, null, 40, 0, 0);
        saveReq(p2);

        Requisition p3 = requisition("LL20261003011", c5, "1#楼", "标准层", "机电预埋", "电施-401",
                "SUBMITTED", true, "孙明", null, t(10, 3, 22), null);
        p3.setRemark("夜间浇筑紧急预埋，先发后补手续");
        item(p3, m6, null, 600, 0, 0);
        saveReq(p3);

        // ---------- 变更签证 ----------
        ChangeVisa v1 = new ChangeVisa();
        v1.setCode("QZ20261002001");
        v1.setBuilding("1#楼");
        v1.setArea("地下室");
        v1.setProcess("防水工程");
        v1.setDrawingNo("建防-201");
        v1.setMaterialId(m3.getId());
        v1.setCrewId(c4.getId());
        v1.setChangeType("DESIGN");
        v1.setDescription("地下室后浇带设计变更，附加层范围扩大，增加卷材并产生拆除损耗");
        v1.setQtyChange(200d);
        v1.setUnitPrice(32d);
        v1.setClaimAmount(6400d);
        v1.setStatus("CONFIRMED");
        v1.setApplicant("赵磊");
        v1.setConfirmer("张项目");
        v1.setCreatedTime(t(10, 2, 13));
        v1.setConfirmTime(t(10, 2, 17));
        visaRepo.save(v1);

        // ---------- 退库 ----------
        MaterialReturn t1 = retDoc("TK20261002001", c5, r6.getId(), "1#楼", "标准层", "机电预埋",
                "SPEC_WRONG", "CONFIRMED", "孙明", "周材料", t(10, 2, 10), t(10, 2, 11),
                "线管规格领错，退回 DN20 改领 DN25");
        retItem(t1, m6, b6, 50, "GOOD", 3.5);
        returnRepo.save(t1);

        MaterialReturn t2 = retDoc("TK20261002002", c4, r3.getId(), "1#楼", "地下室", "防水工程",
                "CHANGE", "CONFIRMED", "赵磊", "周材料", t(10, 2, 14), t(10, 2, 15),
                "施工变更，已领未用卷材退回");
        retItem(t2, m3, b3, 20, "GOOD", 32);
        returnRepo.save(t2);

        MaterialReturn t3 = retDoc("TK20261002003", c3, r4.getId(), "1#楼", "标准层", "墙地砖铺贴",
                "OTHER", "CONFIRMED", "陈海", "周材料", t(10, 2, 16), t(10, 2, 17),
                "搬运磕碰破损 3 箱，损坏退回，责任班组承担");
        retItem(t3, m4, b4, 3, "DAMAGED", 58);
        returnRepo.save(t3);

        // ---------- 损耗 ----------
        waste("SH20261002001", m4, b4, c3, "1#楼", "标准层", "墙地砖铺贴", 5, 58,
                "BREAKAGE", "CREW", null, "切割/搬运破损 5 箱");
        waste("SH20261002002", m5, b5, c2, "1#楼", "标准层", "砌筑抹灰", 0.5, 380,
                "EXPIRED", "CREW", null, "现场堆放超期结块报废，保管不当");
        waste("SH20261002003", m3, b3b, c4, "1#楼", "地下室", "防水工程", 30, 32,
                "QUALITY", "SUPPLIER", null, "补货批次厚度不达标，清退出场并向供应商索赔");
        waste("SH20261002004", m3, b3, c4, "1#楼", "地下室", "防水工程", 10, 32,
                "OTHER", "DESIGN", v1.getId(), "设计变更导致已铺卷材拆除损耗");

        // ---------- 盘点一：劳保/五金尾差（追查中，差异材料冻结） ----------
        StockCheck sc = new StockCheck();
        sc.setCode("PD20261003001");
        sc.setScope("1#楼现场仓库");
        sc.setStatus("COUNTING");
        sc.setRecorder("周材料");
        sc.setCreatedTime(t(10, 3, 9));
        sc.setRemark("月度盘点发现劳保用品与五金账实不符");
        checkItem(sc, m8, b8, 90, 88, 28, "CREW", "安全帽丢失 2 顶，班组照价赔偿");
        checkItem(sc, m7, b7, 4200, 4190, 1.2, "COMPANY", "尾差待查");
        checkRepo.save(sc);

        // ---------- 盘点二：月底瓷砖账实差异 40 箱（本需求主场景，追查中） ----------
        // 瓷砖批次 b4 账面 875 箱（1000-120-5），实盘 835 箱，盘亏 40 箱
        StockCheck tc = new StockCheck();
        tc.setCode("PD20261003002");
        tc.setScope("1#楼现场仓库·瓷砖区");
        tc.setStatus("COUNTING");
        tc.setRecorder("周材料");
        tc.setCreatedTime(t(10, 3, 9, 30));
        tc.setRemark("月底盘点发现瓷砖实际少于系统库存 40 箱，按入库/领用/退库/破损/监控交接追查");
        checkItem(tc, m4, b4, 875, 835, 58, "COMPANY",
                "账面 875、实盘 835；监控指向 30 箱夜间未登记领料，余 10 箱待查");
        checkRepo.save(tc);

        trace(tc, "INBOUND", "MATCHED", m4, "RK20260929001", 1000d,
                "入库单、供应商送货单与质检记录三方核对一致：1000 箱入库无误，排除入库少登。", t(10, 3, 10));
        trace(tc, "REQUISITION", "SUSPECT", m4, "LL20261001004", 120d,
                "有单领用 120 箱与发料记录一致；监控显示 10-02 23:40 另有约 30 箱瓷砖被瓷砖班夜间运出，系统无对应领料单。", t(10, 3, 10, 30));
        trace(tc, "RETURN", "MATCHED", m4, "TK20261002003", 3d,
                "损坏退回 3 箱按班组责任核销、不回补库存，口径与盘点一致，不构成账实差。", t(10, 3, 11));
        trace(tc, "WASTE", "MATCHED", m4, "SH20261002001", 5d,
                "切割/搬运破损 5 箱已登记损耗并核销批次，账实相符。", t(10, 3, 11, 30));
        trace(tc, "MONITOR", "SUSPECT", m4, "门卫交接-1002", 30d,
                "调阅 10-02 22:00 至次日 06:00 监控与门卫交接记录：瓷砖班陈海签字夜间运出瓷砖 30 箱用于标准层赶工，未补办领料；"
                        + "其余 10 箱处于监控盲区，出入库与班组台账均无法定位责任。", t(10, 3, 14));
    }

    // ---------- helpers ----------
    private LocalDateTime t(int month, int day, int hour) {
        return LocalDateTime.of(2026, month, day, hour, 0);
    }

    private LocalDateTime t(int month, int day, int hour, int minute) {
        return LocalDateTime.of(2026, month, day, hour, minute);
    }

    private void trace(StockCheck sc, String type, String result, Material m, String refCode,
                       Double qty, String detail, LocalDateTime time) {
        StockCheckTrace tr = new StockCheckTrace();
        tr.setStockCheck(sc);
        tr.setTraceType(type);
        tr.setResult(result);
        tr.setMaterialId(m == null ? null : m.getId());
        tr.setRefCode(refCode);
        tr.setQty(qty);
        tr.setDetail(detail);
        tr.setRecorder("周材料");
        tr.setCreatedTime(time);
        traceRepo.save(tr);
    }

    private Crew crew(String name, String leader, String trade) {
        Crew c = new Crew();
        c.setName(name);
        c.setLeader(leader);
        c.setTrade(trade);
        return c;
    }

    private void user(String username, String pwd, String name, String role, Long crewId) {
        User u = new User();
        u.setUsername(username);
        u.setPassword(pwd);
        u.setDisplayName(name);
        u.setRole(role);
        u.setCrewId(crewId);
        userRepo.save(u);
    }

    private Supplier supplier(String name, String contact, String phone) {
        Supplier s = new Supplier();
        s.setName(name);
        s.setContact(contact);
        s.setPhone(phone);
        return s;
    }

    private Material material(String name, String category, String spec, String unit, double safety) {
        Material m = new Material();
        m.setName(name);
        m.setCategory(category);
        m.setSpec(spec);
        m.setUnit(unit);
        m.setSafetyStock(safety);
        return materialRepo.save(m);
    }

    private MaterialBatch batch(String no, Material m, String spec, Supplier s, double qtyIn,
                                double price, String qc, double issued, double returned,
                                double wasted, double adjust, LocalDateTime time) {
        MaterialBatch b = new MaterialBatch();
        b.setBatchNo(no);
        b.setMaterialId(m.getId());
        b.setSpec(spec);
        b.setSupplierId(s.getId());
        b.setQtyInbound(qtyIn);
        b.setQtyIssued(issued);
        b.setQtyReturned(returned);
        b.setQtyWasted(wasted);
        b.setQtyAdjust(adjust);
        b.setUnitPrice(price);
        b.setQcStatus(qc);
        b.setInboundTime(time);
        return batchRepo.save(b);
    }

    private void budget(String building, String area, String process, String drawing,
                        Material m, double qty, double price, Crew crew) {
        ProjectBudget b = new ProjectBudget();
        b.setBuilding(building);
        b.setArea(area);
        b.setProcess(process);
        b.setDrawingNo(drawing);
        b.setMaterialId(m.getId());
        b.setBudgetQty(qty);
        b.setBudgetPrice(price);
        b.setCrewId(crew.getId());
        budgetRepo.save(b);
    }

    private Requisition requisition(String code, Crew crew, String building, String area,
                                    String process, String drawing, String status, boolean urgent,
                                    String applicant, String issuedBy,
                                    LocalDateTime applyTime, LocalDateTime issueTime) {
        Requisition r = new Requisition();
        r.setCode(code);
        r.setCrewId(crew.getId());
        r.setBuilding(building);
        r.setArea(area);
        r.setProcess(process);
        r.setDrawingNo(drawing);
        r.setStatus(status);
        r.setUrgent(urgent);
        r.setApplicant(applicant);
        r.setIssuedBy(issuedBy);
        r.setConfirmedBy("ISSUED".equals(status) ? applicant : null);
        r.setApplyTime(applyTime);
        r.setIssueTime(issueTime);
        return r;
    }

    private void item(Requisition r, Material m, MaterialBatch b, double apply, double issued, double price) {
        RequisitionItem i = new RequisitionItem();
        i.setRequisition(r);
        i.setMaterialId(m.getId());
        i.setBatchId(b == null ? null : b.getId());
        i.setApplyQty(apply);
        i.setIssuedQty(issued);
        i.setIssuePrice(price);
        r.getItems().add(i);
    }

    private void saveReq(Requisition r) {
        reqRepo.save(r);
    }

    private MaterialReturn retDoc(String code, Crew crew, Long reqId, String building, String area,
                                  String process, String reason, String status, String applicant,
                                  String confirmer, LocalDateTime apply, LocalDateTime confirm,
                                  String remark) {
        MaterialReturn r = new MaterialReturn();
        r.setCode(code);
        r.setCrewId(crew.getId());
        r.setRequisitionId(reqId);
        r.setBuilding(building);
        r.setArea(area);
        r.setProcess(process);
        r.setReasonType(reason);
        r.setStatus(status);
        r.setApplicant(applicant);
        r.setConfirmedBy(confirmer);
        r.setApplyTime(apply);
        r.setConfirmTime(confirm);
        r.setRemark(remark);
        return r;
    }

    private void retItem(MaterialReturn doc, Material m, MaterialBatch b, double qty,
                         String condition, double price) {
        ReturnItem i = new ReturnItem();
        i.setMaterialReturn(doc);
        i.setMaterialId(m.getId());
        i.setBatchId(b == null ? null : b.getId());
        i.setQty(qty);
        i.setConditionType(condition);
        i.setPrice(price);
        doc.getItems().add(i);
    }

    private void waste(String code, Material m, MaterialBatch b, Crew crew, String building,
                       String area, String process, double qty, double price, String type,
                       String resp, Long visaId, String desc) {
        WasteRecord w = new WasteRecord();
        w.setCode(code);
        w.setMaterialId(m.getId());
        w.setBatchId(b.getId());
        w.setCrewId(crew.getId());
        w.setBuilding(building);
        w.setArea(area);
        w.setProcess(process);
        w.setQty(qty);
        w.setUnitPrice(price);
        w.setWasteType(type);
        w.setResponsibility(resp);
        w.setVisaId(visaId);
        w.setDescription(desc);
        w.setRecorder("周材料");
        w.setCreatedTime(t(10, 2, 16));
        wasteRepo.save(w);
    }

    private void checkItem(StockCheck sc, Material m, MaterialBatch b, double book, double actual,
                           double price, String resp, String remark) {
        StockCheckItem i = new StockCheckItem();
        i.setStockCheck(sc);
        i.setMaterialId(m.getId());
        i.setBatchId(b.getId());
        i.setBookQty(book);
        i.setActualQty(actual);
        i.setUnitPrice(price);
        i.setResponsibility(resp);
        i.setRemark(remark);
        sc.getItems().add(i);
    }
}
