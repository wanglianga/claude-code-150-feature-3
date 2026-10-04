package com.site.material;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 全链路集成测试（H2 内存库 + 种子数据）：
 * 登录 -> 发料核对 -> 紧急发料 -> 退库确认 ->
 * 盘点账实差异追查（限制领用 -> 监控交接线索 -> 补录班组领料 -> 公共损耗 -> 进度影响确认 -> 关闭调账）
 * -> 受限班组领料审批 -> 月底核算 -> 偏差/复盘/财务分析。
 */
@SpringBootTest
@AutoConfigureMockMvc
class MaterialFlowIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;

    private String token(String username, String password) throws Exception {
        String body = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("username", username, "password", password))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return om.readTree(body).get("token").asText();
    }

    private JsonNode getJson(String url, String token) throws Exception {
        return om.readTree(mvc.perform(get(url).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void fullMaterialLifecycleAndSettlement() throws Exception {
        String keeper = token("keeper", "keeper123");
        String finance = token("finance", "finance123");
        String foreman = token("foreman5", "foreman123");
        String pm = token("pm", "pm123");

        // 1. 库存总览有数据
        mvc.perform(get("/api/inventory").header("Authorization", "Bearer " + keeper))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].materialName").exists());

        // 2. 待发料单：夜间紧急单（p3 机电班 DN20 600 米）核对，库存 2650（3000-400+50）
        JsonNode reqs = getJson("/api/requisitions?status=SUBMITTED", keeper);
        long urgentId = -1;
        for (JsonNode r : reqs) {
            if (r.get("urgent").asBoolean()) urgentId = r.get("id").asLong();
        }
        assertTrue(urgentId > 0, "应存在夜间紧急领料单");

        JsonNode pre = getJson("/api/requisitions/" + urgentId + "/precheck", keeper);
        JsonNode check = pre.get("checks").get(0);
        assertTrue(check.get("stockEnough").asBoolean(), "紧急单库存应充足");
        assertEquals(2000d, check.get("budgetQty").asDouble(), 0.01);
        assertEquals(400d, check.get("alreadyIssued").asDouble(), 0.01);

        // 3. 仓管发料：选择第一个可用批次
        long itemId = check.get("itemId").asLong();
        long batchId = check.get("batches").get(0).get("batchId").asLong();
        String issueBody = om.writeValueAsString(Map.of("items",
                List.of(Map.of("itemId", itemId, "batchId", batchId, "issuedQty", 600d))));
        mvc.perform(put("/api/requisitions/" + urgentId + "/issue")
                        .header("Authorization", "Bearer " + keeper)
                        .contentType(MediaType.APPLICATION_JSON).content(issueBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requisition.status").value("ISSUED"));

        // 4. 库存不足时发料应被拒绝（钢筋班 p1 发 999 吨应报库存不足）
        JsonNode p1List = getJson("/api/requisitions?status=SUBMITTED", keeper);
        long steelId = -1, steelItem = -1, steelBatch = -1;
        for (JsonNode r : p1List) {
            if ("主体结构".equals(r.get("process").asText()) && r.get("crewId").asLong() == 1) {
                steelId = r.get("id").asLong();
                steelItem = r.get("items").get(0).get("itemId").asLong();
            }
        }
        JsonNode steelPre = getJson("/api/requisitions/" + steelId + "/precheck", keeper);
        steelBatch = steelPre.get("checks").get(0).get("batches").get(0).get("batchId").asLong();
        String badIssue = om.writeValueAsString(Map.of("items",
                List.of(Map.of("itemId", steelItem, "batchId", steelBatch, "issuedQty", 999d))));
        mvc.perform(put("/api/requisitions/" + steelId + "/issue")
                        .header("Authorization", "Bearer " + keeper)
                        .contentType(MediaType.APPLICATION_JSON).content(badIssue))
                .andExpect(status().isBadRequest());

        // 正常发 3 吨（累计 15 < 20 预算，不超）
        String okIssue = om.writeValueAsString(Map.of("items",
                List.of(Map.of("itemId", steelItem, "batchId", steelBatch, "issuedQty", 3d))));
        mvc.perform(put("/api/requisitions/" + steelId + "/issue")
                        .header("Authorization", "Bearer " + keeper)
                        .contentType(MediaType.APPLICATION_JSON).content(okIssue))
                .andExpect(status().isOk());

        // 5. 班组长提交退库，仓管确认入库（规格领错 20 米 DN20）
        String retBody = om.writeValueAsString(Map.of(
                "crewId", 5, "building", "1#楼", "area", "标准层", "process", "机电预埋",
                "reasonType", "SPEC_WRONG",
                "items", List.of(Map.of("materialId", 6, "batchId", batchId, "qty", 20d,
                        "conditionType", "GOOD", "price", 3.5))));
        JsonNode ret = om.readTree(mvc.perform(post("/api/returns")
                        .header("Authorization", "Bearer " + foreman)
                        .contentType(MediaType.APPLICATION_JSON).content(retBody))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        long retId = ret.get("id").asLong();
        mvc.perform(put("/api/returns/" + retId + "/confirm")
                        .header("Authorization", "Bearer " + keeper))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        // ==================== 6. 月底盘点账实差异追查（瓷砖盘亏 20 箱） ====================
        // 6.1 盘点单 PD20261004001 追查中：账面 875 实盘 855，差异未关闭
        JsonNode checks = getJson("/api/stock-checks", keeper);
        long checkId = -1, tileItemId = -1;
        for (JsonNode sc : checks) {
            if ("PD20261004001".equals(sc.get("code").asText())) {
                checkId = sc.get("id").asLong();
                tileItemId = sc.get("items").get(0).get("itemId").asLong();
                assertEquals("COUNTING", sc.get("status").asText());
                assertEquals(-20d, sc.get("items").get(0).get("diffQty").asDouble(), 0.01);
                assertFalse(sc.get("canClose").asBoolean(), "差异未处理前不可关闭");
            }
        }
        assertTrue(checkId > 0, "应存在月底瓷砖盘点单");

        // 6.2 差异关闭前，瓷砖（m4）限制继续领用：瓷砖班待发料单核对受限、发料被拦截
        JsonNode submitted = getJson("/api/requisitions?status=SUBMITTED", keeper);
        long tileReqId = -1, tileReqItem = -1;
        for (JsonNode r : submitted) {
            if (r.get("crewId").asLong() == 3 && "墙地砖铺贴".equals(r.get("process").asText())) {
                tileReqId = r.get("id").asLong();
                tileReqItem = r.get("items").get(0).get("itemId").asLong();
            }
        }
        assertTrue(tileReqId > 0, "应存在瓷砖班待发料单");
        JsonNode tilePre = getJson("/api/requisitions/" + tileReqId + "/precheck", keeper);
        assertTrue(tilePre.get("checks").get(0).get("restricted").asBoolean(),
                "盘点差异未关闭，材料应限制领用");
        long tileBatch = tilePre.get("checks").get(0).get("batches").get(0).get("batchId").asLong();
        mvc.perform(put("/api/requisitions/" + tileReqId + "/issue")
                        .header("Authorization", "Bearer " + keeper)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("items",
                                List.of(Map.of("itemId", tileReqItem, "batchId", tileBatch, "issuedQty", 40d))))))
                .andExpect(status().isBadRequest());

        // 6.3 追查：入库/领用/退库/损耗/监控交接线索齐全
        JsonNode trace = getJson("/api/stock-checks/" + checkId + "/trace", keeper);
        JsonNode traceItem = trace.get("items").get(0);
        assertEquals(20d, traceItem.get("lossQty").asDouble(), 0.01);
        assertTrue(traceItem.get("inbound").size() >= 1, "追查应含入库批次");
        boolean hasTileReq = false;
        for (JsonNode r : traceItem.get("requisitions")) {
            if ("LL20261001004".equals(r.get("code").asText())) hasTileReq = true;
        }
        assertTrue(hasTileReq, "追查应含历史领用记录");
        assertTrue(trace.get("handovers").size() >= 3, "追查应含监控交接记录");
        boolean hasClue = false;
        for (JsonNode h : trace.get("handovers")) {
            if (h.get("note").asText().contains("陈海") && h.get("relatedCrewId").asLong() == 3) hasClue = true;
        }
        assertTrue(hasClue, "监控交接应给出瓷砖班未登记领料线索");

        // 6.4 补录班组领料 12 箱：生成盘点补录单（已发料），批次已领增加，班组被限制
        mvc.perform(post("/api/stock-checks/" + checkId + "/items/" + tileItemId + "/backfill")
                        .header("Authorization", "Bearer " + keeper)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "crewId", 3, "qty", 12d, "building", "1#楼", "area", "标准层",
                                "process", "墙地砖铺贴", "remark", "监控核实夜间未登记领料"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].backfillQty").value(12d))
                .andExpect(jsonPath("$.items[0].remainingQty").value(8d));
        JsonNode crewReqs = getJson("/api/requisitions?crewId=3", keeper);
        boolean hasBackfill = false;
        for (JsonNode r : crewReqs) {
            if (r.get("backfill").asBoolean()) {
                hasBackfill = true;
                assertEquals("ISSUED", r.get("status").asText());
                assertEquals(696d, r.get("totalAmount").asDouble(), 0.01); // 12*58 计入班组成本
            }
        }
        assertTrue(hasBackfill, "应生成盘点补录领料单");
        JsonNode batches4 = getJson("/api/batches?materialId=4", keeper);
        assertEquals(863d, batches4.get(0).get("onHand").asDouble(), 0.01, "补录后批次账面 875-12=863");

        // 6.5 班组被限制：新领料需项目经理审批
        JsonNode crews = getJson("/api/crews", keeper);
        boolean restricted = false;
        for (JsonNode c : crews) {
            if (c.get("id").asLong() == 3) restricted = c.get("requisitionRestricted").asBoolean();
        }
        assertTrue(restricted, "补录后瓷砖班应被限制领料审批");
        String foreman3 = token("foreman3", "foreman123");
        JsonNode pending = om.readTree(mvc.perform(post("/api/requisitions")
                        .header("Authorization", "Bearer " + foreman3)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "crewId", 3, "building", "1#楼", "process", "墙地砖铺贴",
                                "items", List.of(Map.of("materialId", 4, "applyQty", 5d))))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals("PENDING_PM", pending.get("status").asText(), "受限班组领料应待项目经理审批");
        // 待审批单不能直接发料
        mvc.perform(put("/api/requisitions/" + pending.get("id").asLong() + "/issue")
                        .header("Authorization", "Bearer " + keeper)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("items",
                                List.of(Map.of("itemId", pending.get("items").get(0).get("id").asLong(),
                                        "batchId", tileBatch, "issuedQty", 5d))))))
                .andExpect(status().isBadRequest());
        // 项目经理审批后进入待发料
        mvc.perform(put("/api/requisitions/" + pending.get("id").asLong() + "/approve")
                        .header("Authorization", "Bearer " + pm))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"));

        // 6.6 剩余 8 箱无法定位责任，转入项目公共损耗
        mvc.perform(post("/api/stock-checks/" + checkId + "/items/" + tileItemId + "/common-loss")
                        .header("Authorization", "Bearer " + keeper)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "qty", 8d, "building", "1#楼", "remark", "尾差无法定位责任"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].commonLossQty").value(8d))
                .andExpect(jsonPath("$.items[0].remainingQty").value(0d));

        // 6.7 关闭守卫：项目经理未确认进度影响前不能关闭；仓管不能代确认
        mvc.perform(put("/api/stock-checks/" + checkId + "/close")
                        .header("Authorization", "Bearer " + keeper))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/stock-checks/" + checkId + "/impact")
                        .header("Authorization", "Bearer " + keeper)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("impactLevel", "NONE"))))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/stock-checks/" + checkId + "/impact")
                        .header("Authorization", "Bearer " + pm)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("impactLevel", "MINOR",
                                "impactNote", "瓷砖暂停领用影响标准层铺贴 1 天，已协调从 2#楼 调拨应急"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.impactLevel").value("MINOR"));

        // 6.8 关闭调账：账面 = 实盘 855，材料解除领用限制
        mvc.perform(put("/api/stock-checks/" + checkId + "/close")
                        .header("Authorization", "Bearer " + keeper))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
        batches4 = getJson("/api/batches?materialId=4", keeper);
        assertEquals(855d, batches4.get(0).get("onHand").asDouble(), 0.01, "关闭后账面应等于实盘");
        tilePre = getJson("/api/requisitions/" + tileReqId + "/precheck", keeper);
        assertFalse(tilePre.get("checks").get(0).get("restricted").asBoolean(),
                "差异关闭后材料应恢复领用");

        // 6.9 项目经理核实后解除班组限制
        mvc.perform(put("/api/crews/3/restriction")
                        .header("Authorization", "Bearer " + pm)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("restricted", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requisitionRestricted").value(false));

        // 7. 月底核算：机电班（crewId=5）2026-10
        String genBody = om.writeValueAsString(Map.of("period", "2026-10", "crewId", 5));
        JsonNode st = om.readTree(mvc.perform(post("/api/settlements/generate")
                        .header("Authorization", "Bearer " + finance)
                        .contentType(MediaType.APPLICATION_JSON).content(genBody))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        long settlementId = st.get("id").asLong();
        // 机电班领用 400*3.5+800*1.2+紧急600*3.5 = 4460；退库 50*3.5+20*3.5 = 245
        assertEquals(4460d, st.get("issuedAmount").asDouble(), 0.01);
        assertEquals(245d, st.get("returnedAmount").asDouble(), 0.01);
        assertEquals(4215d, st.get("consumedAmount").asDouble(), 0.01);
        // 预算 2000*3.5 + 3000*1.2 = 10600；净消耗低于预算 => 偏差为负，扣款仅责任损耗 0
        assertEquals(-6385d, st.get("varianceAmount").asDouble(), 0.01);
        assertEquals(0d, st.get("deductionAmount").asDouble(), 0.01);

        // 财务确认核算单
        mvc.perform(put("/api/settlements/" + settlementId + "/confirm")
                        .header("Authorization", "Bearer " + finance))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
        // 已确认不可重复生成
        mvc.perform(post("/api/settlements/generate")
                        .header("Authorization", "Bearer " + finance)
                        .contentType(MediaType.APPLICATION_JSON).content(genBody))
                .andExpect(status().isBadRequest());

        // 7.1 瓷砖班核算：补录领料 12*58=696 计入班组成本（领用 120*58+696=7656）
        JsonNode st3 = om.readTree(mvc.perform(post("/api/settlements/generate")
                        .header("Authorization", "Bearer " + finance)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("period", "2026-10", "crewId", 3))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals(7656d, st3.get("issuedAmount").asDouble(), 0.01, "补录领料应计入班组成本");
        assertEquals(0d, st3.get("returnedAmount").asDouble(), 0.01);
        // 班组责任损耗 = 损坏退库 3*58 + 破损 5*58 = 464；公共损耗不归集到班组
        assertEquals(464d, st3.get("crewWasteAmount").asDouble(), 0.01);
        assertEquals(464d, st3.get("deductionAmount").asDouble(), 0.01);

        // 8. 项目经理：按楼栋/工序/班组查看偏差
        mvc.perform(get("/api/analytics/consumption")
                        .param("building", "1#楼").param("crewId", "5")
                        .header("Authorization", "Bearer " + pm))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].netConsumedAmount").exists());

        // 9. 材料员损耗复盘：四类原因汇总，含设计变更与供应商质量；公共损耗进"其他"
        JsonNode review = getJson("/api/analytics/waste-review", keeper);
        assertTrue(review.get("totalAmount").asDouble() > 0);
        boolean hasDesign = false, hasQuality = false, hasCommonLoss = false;
        for (JsonNode s : review.get("summary")) {
            if ("DESIGN".equals(s.get("cause").asText()) && s.get("amount").asDouble() > 0) hasDesign = true;
            if ("QUALITY".equals(s.get("cause").asText()) && s.get("amount").asDouble() > 0) hasQuality = true;
            if ("OTHER".equals(s.get("cause").asText()) && s.get("amount").asDouble() >= 532)
                hasCommonLoss = true; // 公共损耗 8*58 + 2*28 + 10*1.2 = 532
        }
        assertTrue(hasDesign && hasQuality, "复盘应同时包含设计变更与供应商质量损耗");
        assertTrue(hasCommonLoss, "复盘应包含盘点转入的项目公共损耗");

        // 10. 预算员采购计划 + 财务汇总
        mvc.perform(get("/api/analytics/procurement-plan")
                        .param("building", "1#楼")
                        .header("Authorization", "Bearer " + token("budget", "budget123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].suggestPurchaseQty").exists());
        JsonNode fin = getJson("/api/analytics/finance", finance);
        assertTrue(fin.get("supplierTotalPayable").asDouble() > 0);
        assertTrue(fin.get("supplierQualityClaim").asDouble() >= 960); // 30*32

        // 11. 权限：班组长不能登记损耗
        mvc.perform(post("/api/wastes")
                        .header("Authorization", "Bearer " + foreman)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "materialId", 4, "building", "1#楼", "qty", 1d,
                                "wasteType", "BREAKAGE", "responsibility", "CREW"))))
                .andExpect(status().isForbidden());

        // 12. 无 token 访问受保护接口 401
        mvc.perform(get("/api/inventory")).andExpect(status().isUnauthorized());
    }
}
