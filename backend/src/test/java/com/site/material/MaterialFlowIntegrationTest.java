package com.site.material;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 全链路集成测试（H2 内存库 + 种子数据）：
 * 登录 -> 发料核对（预算/已领/库存）-> 紧急发料 -> 退库确认 ->
 * 月底盘点账实差异追查（冻结拦截/监控交接/公共损耗/夜料补录/班组加严审批/PM 进度确认/关闭解冻）->
 * 月底核算（补录夜料计入班组成本）-> 偏差/复盘/财务分析。
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
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return om.readTree(body).get("token").asText();
    }

    private JsonNode json(String s) throws Exception {
        return om.readTree(s);
    }

    @Test
    void fullMaterialLifecycleAndSettlement() throws Exception {
        String keeper = token("keeper", "keeper123");
        String finance = token("finance", "finance123");
        String pm = token("pm", "pm123");
        String foreman = token("foreman5", "foreman123");
        String foreman3 = token("foreman3", "foreman123");

        // 1. 库存总览有数据，瓷砖(materialId=4)处于盘点冻结
        JsonNode inv = json(mvc.perform(get("/api/inventory").header("Authorization", "Bearer " + keeper))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(inv.get("frozenCount").asInt() >= 1, "应存在盘点冻结材料");
        boolean tileFrozen = false;
        for (JsonNode row : inv.get("rows")) {
            if (row.get("materialId").asLong() == 4 && row.has("frozenByCheck") && !row.get("frozenByCheck").isNull()) {
                tileFrozen = true;
            }
        }
        assertTrue(tileFrozen, "瓷砖盘点差异未关闭前应冻结");

        // 2. 待发料单：夜间紧急单（p3 机电班 DN20 600 米）核对，库存 2650（3000-400+50）
        JsonNode reqs = json(mvc.perform(get("/api/requisitions?status=SUBMITTED")
                        .header("Authorization", "Bearer " + keeper))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        long urgentId = -1;
        for (JsonNode r : reqs) {
            if (r.get("urgent").asBoolean()) urgentId = r.get("id").asLong();
        }
        assertTrue(urgentId > 0, "应存在夜间紧急领料单");

        JsonNode pre = json(mvc.perform(get("/api/requisitions/" + urgentId + "/precheck")
                        .header("Authorization", "Bearer " + keeper))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
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

        // 4. 库存不足发料被拒绝；正常发 3 吨钢筋（非冻结材料，不受影响）
        JsonNode p1List = json(mvc.perform(get("/api/requisitions?status=SUBMITTED")
                        .header("Authorization", "Bearer " + keeper))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        long steelId = -1, steelItem = -1, steelBatch = -1;
        for (JsonNode r : p1List) {
            if ("主体结构".equals(r.get("process").asText()) && r.get("crewId").asLong() == 1) {
                steelId = r.get("id").asLong();
                steelItem = r.get("items").get(0).get("itemId").asLong();
            }
        }
        JsonNode steelPre = json(mvc.perform(get("/api/requisitions/" + steelId + "/precheck")
                .header("Authorization", "Bearer " + keeper)).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        steelBatch = steelPre.get("checks").get(0).get("batches").get(0).get("batchId").asLong();
        mvc.perform(put("/api/requisitions/" + steelId + "/issue")
                        .header("Authorization", "Bearer " + keeper)
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(Map.of("items",
                                List.of(Map.of("itemId", steelItem, "batchId", steelBatch, "issuedQty", 999d))))))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/requisitions/" + steelId + "/issue")
                        .header("Authorization", "Bearer " + keeper)
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(Map.of("items",
                                List.of(Map.of("itemId", steelItem, "batchId", steelBatch, "issuedQty", 3d))))))
                .andExpect(status().isOk());

        // 5. 班组长提交退库，仓管确认入库（规格领错 20 米 DN20）
        String retBody = om.writeValueAsString(Map.of(
                "crewId", 5, "building", "1#楼", "area", "标准层", "process", "机电预埋",
                "reasonType", "SPEC_WRONG",
                "items", List.of(Map.of("materialId", 6, "batchId", batchId, "qty", 20d,
                        "conditionType", "GOOD", "price", 3.5))));
        JsonNode ret = json(mvc.perform(post("/api/returns")
                        .header("Authorization", "Bearer " + foreman)
                        .contentType(MediaType.APPLICATION_JSON).content(retBody))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        long retId = ret.get("id").asLong();
        mvc.perform(put("/api/returns/" + retId + "/confirm")
                        .header("Authorization", "Bearer " + keeper))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        // ========== 6. 月底瓷砖盘点差异追查（PD20261003002，盘亏 40 箱） ==========
        JsonNode checks = json(mvc.perform(get("/api/stock-checks")
                        .header("Authorization", "Bearer " + keeper))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        long tileCheckId = -1;
        long tileItemId = -1;
        for (JsonNode sc : checks) {
            if ("PD20261003002".equals(sc.get("code").asText())) {
                tileCheckId = sc.get("id").asLong();
                tileItemId = sc.get("items").get(0).get("itemId").asLong();
                assertEquals(40d, sc.get("items").get(0).get("shortageQty").asDouble(), 0.01);
                assertTrue(sc.get("frozen").asBoolean());
            }
        }
        assertTrue(tileCheckId > 0);

        // 6.1 冻结期：瓷砖班 40 箱待发料单（种子 p2）发料必须被拦截
        long tileReqId = -1, tileReqItem = -1;
        for (JsonNode r : p1List) {
            if (r.get("crewId").asLong() == 3 && "墙地砖铺贴".equals(r.get("process").asText())) {
                tileReqId = r.get("id").asLong();
                tileReqItem = r.get("items").get(0).get("itemId").asLong();
            }
        }
        JsonNode tileReqPre = json(mvc.perform(get("/api/requisitions/" + tileReqId + "/precheck")
                .header("Authorization", "Bearer " + keeper)).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        long tileBatch = tileReqPre.get("checks").get(0).get("batches").get(0).get("batchId").asLong();
        mvc.perform(put("/api/requisitions/" + tileReqId + "/issue")
                        .header("Authorization", "Bearer " + keeper)
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(Map.of("items",
                                List.of(Map.of("itemId", tileReqItem, "batchId", tileBatch, "issuedQty", 40d))))))
                .andExpect(status().isBadRequest());
        // 冻结期：班组长新申请瓷砖领料也被拒绝
        mvc.perform(post("/api/requisitions")
                        .header("Authorization", "Bearer " + foreman3)
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(Map.of(
                                "crewId", 3, "building", "1#楼", "process", "墙地砖铺贴",
                                "items", List.of(Map.of("materialId", 4, "applyQty", 5d))))))
                .andExpect(status().isBadRequest());

        // 6.2 仓管补登监控交接轨迹；班组长无权登记（403）
        mvc.perform(post("/api/stock-checks/" + tileCheckId + "/traces")
                        .header("Authorization", "Bearer " + foreman3)
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(Map.of(
                                "traceType", "MONITOR", "result", "SUSPECT", "materialId", 4,
                                "refCode", "门卫交接-1002", "qty", 30d, "detail", "无权操作"))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/stock-checks/" + tileCheckId + "/traces")
                        .header("Authorization", "Bearer " + keeper)
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(Map.of(
                                "traceType", "MONITOR", "result", "CONFIRMED", "materialId", 4,
                                "refCode", "门卫交接-1002", "qty", 30d,
                                "detail", "二次约谈瓷砖班确认夜间运出 30 箱，另有 10 箱监控盲区无法定位"))))
                .andExpect(status().isOk());

        // 6.3 盘亏未全部定性时不允许关闭
        mvc.perform(put("/api/stock-checks/" + tileCheckId + "/close")
                        .header("Authorization", "Bearer " + keeper))
                .andExpect(status().isBadRequest());

        // 6.4 补录 30 箱班组夜间未登记领料：回写批次已发、计入班组成本、加严审批
        JsonNode sup = json(mvc.perform(post("/api/stock-checks/" + tileCheckId + "/supplement-night")
                        .header("Authorization", "Bearer " + keeper)
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(Map.of(
                                "itemId", tileItemId, "crewId", 3, "building", "1#楼", "area", "标准层",
                                "process", "墙地砖铺贴", "drawingNo", "建装-305", "qty", 30d,
                                "actualTime", "2026-10-02T23:40",
                                "remark", "监控+门卫交接+班组确认"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(sup.get("requisitionCode").asText().startsWith("BL"));
        assertTrue(sup.get("crewRestricted").asBoolean());

        // 6.5 剩余 10 箱无法定位责任 -> 项目公共损耗（核销批次，不扣班组）；超额定性被拒绝
        mvc.perform(post("/api/stock-checks/" + tileCheckId + "/common-loss")
                        .header("Authorization", "Bearer " + keeper)
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(Map.of(
                                "itemId", tileItemId, "qty", 11d, "remark", "超额应失败"))))
                .andExpect(status().isBadRequest());
        JsonNode loss = json(mvc.perform(post("/api/stock-checks/" + tileCheckId + "/common-loss")
                        .header("Authorization", "Bearer " + keeper)
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(Map.of(
                                "itemId", tileItemId, "qty", 10d, "remark", "监控盲区，无法定位责任"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(loss.get("wasteCode").asText().startsWith("SH"));

        // 6.6 未确认进度影响仍不能关闭；仓管无权代 PM 确认（403）
        mvc.perform(put("/api/stock-checks/" + tileCheckId + "/close")
                        .header("Authorization", "Bearer " + keeper))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/stock-checks/" + tileCheckId + "/progress")
                        .header("Authorization", "Bearer " + keeper)
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(Map.of(
                                "impact", "NO", "remark", "库存可满足后续铺贴"))))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/stock-checks/" + tileCheckId + "/progress")
                        .header("Authorization", "Bearer " + pm)
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(Map.of(
                                "impact", "NO", "remark", "库存 835 箱可满足后续铺贴，不影响施工进度"))))
                .andExpect(status().isOk());

        // 6.7 关闭盘点 -> CLOSED，解除瓷砖冻结
        mvc.perform(put("/api/stock-checks/" + tileCheckId + "/close")
                        .header("Authorization", "Bearer " + keeper))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.conclusion").value("MIXED"));

        // 6.8 瓷砖班当月核算（在补发冻结单之前，保证口径确定）：
        //   补录夜料 30 箱计入班组成本
        //   领用 = 120*58(有单) + 30*58(夜料补录) = 8700；预算 150*58 = 8700；偏差 0；
        //   班组责任损耗 = 破损 5*58 + 损坏退库 3*58 = 464 -> 扣款 464
        JsonNode tileSettle = json(mvc.perform(post("/api/settlements/generate")
                        .header("Authorization", "Bearer " + finance)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("period", "2026-10", "crewId", 3))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(8700d, tileSettle.get("issuedAmount").asDouble(), 0.01);
        assertEquals(8700d, tileSettle.get("budgetAmount").asDouble(), 0.01);
        assertEquals(0d, tileSettle.get("varianceAmount").asDouble(), 0.01);
        assertEquals(464d, tileSettle.get("crewWasteAmount").asDouble(), 0.01);
        assertEquals(464d, tileSettle.get("deductionAmount").asDouble(), 0.01);

        // 6.9 关闭后原待发料 40 箱瓷砖可以正常发料（冻结解除）
        mvc.perform(put("/api/requisitions/" + tileReqId + "/issue")
                        .header("Authorization", "Bearer " + keeper)
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(Map.of("items",
                                List.of(Map.of("itemId", tileReqItem, "batchId", tileBatch, "issuedQty", 40d))))))
                .andExpect(status().isOk());

        // 6.10 受限班组：瓷砖班新领料单自动转 PENDING_PM，仓管不能直接发，PM 批准后可发
        JsonNode newReq = json(mvc.perform(post("/api/requisitions")
                        .header("Authorization", "Bearer " + foreman3)
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(Map.of(
                                "crewId", 3, "building", "1#楼", "process", "墙地砖铺贴",
                                "items", List.of(Map.of("materialId", 2, "applyQty", 10d))))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        long newReqId = newReq.get("id").asLong();
        assertEquals("PENDING_PM", newReq.get("status").asText(), "受限班组新单须 PM 审批");
        JsonNode newPre = json(mvc.perform(get("/api/requisitions/" + newReqId + "/precheck")
                .header("Authorization", "Bearer " + keeper)).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        long newItem = newPre.get("checks").get(0).get("itemId").asLong();
        long newBatch = newPre.get("checks").get(0).get("batches").get(0).get("batchId").asLong();
        mvc.perform(put("/api/requisitions/" + newReqId + "/issue")
                        .header("Authorization", "Bearer " + keeper)
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(Map.of("items",
                                List.of(Map.of("itemId", newItem, "batchId", newBatch, "issuedQty", 10d))))))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/requisitions/" + newReqId + "/pm-approve")
                        .header("Authorization", "Bearer " + pm)
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(Map.of("remark", "同意发料"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"));
        mvc.perform(put("/api/requisitions/" + newReqId + "/issue")
                        .header("Authorization", "Bearer " + keeper)
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(Map.of("items",
                                List.of(Map.of("itemId", newItem, "batchId", newBatch, "issuedQty", 10d))))))
                .andExpect(status().isOk());
        // 仓管无权解除班组限制（403），PM 解除后恢复正常
        mvc.perform(put("/api/crews/3/release-restriction").header("Authorization", "Bearer " + keeper))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/crews/3/release-restriction").header("Authorization", "Bearer " + pm))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requisitionRestricted").value(false));

        // 7. 月底核算：机电班（crewId=5）2026-10
        String genBody = om.writeValueAsString(Map.of("period", "2026-10", "crewId", 5));
        JsonNode st = json(mvc.perform(post("/api/settlements/generate")
                        .header("Authorization", "Bearer " + finance)
                        .contentType(MediaType.APPLICATION_JSON).content(genBody))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        long settlementId = st.get("id").asLong();
        // 机电班领用 400*3.5+800*1.2+紧急600*3.5 = 4460；退库 50*3.5+20*3.5 = 245
        assertEquals(4460d, st.get("issuedAmount").asDouble(), 0.01);
        assertEquals(245d, st.get("returnedAmount").asDouble(), 0.01);
        assertEquals(4215d, st.get("consumedAmount").asDouble(), 0.01);
        assertEquals(-6385d, st.get("varianceAmount").asDouble(), 0.01);
        assertEquals(0d, st.get("deductionAmount").asDouble(), 0.01);

        mvc.perform(put("/api/settlements/" + settlementId + "/confirm")
                        .header("Authorization", "Bearer " + finance))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
        mvc.perform(post("/api/settlements/generate")
                        .header("Authorization", "Bearer " + finance)
                        .contentType(MediaType.APPLICATION_JSON).content(genBody))
                .andExpect(status().isBadRequest());

        // 8. 项目经理：按楼栋/工序/班组查看偏差
        mvc.perform(get("/api/analytics/consumption")
                        .param("building", "1#楼").param("crewId", "5")
                        .header("Authorization", "Bearer " + pm))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].netConsumedAmount").exists());

        // 9. 材料员损耗复盘：四类原因汇总，含设计变更、供应商质量与公共损耗
        JsonNode review = json(mvc.perform(get("/api/analytics/waste-review")
                        .header("Authorization", "Bearer " + keeper))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(review.get("totalAmount").asDouble() > 0);
        boolean hasDesign = false, hasQuality = false, hasCommonOther = false;
        for (JsonNode x : review.get("detail")) {
            if ("DESIGN".equals(x.get("responsibility").asText())) hasDesign = true;
            if ("SUPPLIER".equals(x.get("responsibility").asText())) hasQuality = true;
            // 盘点无法定位责任的差异计入项目承担（COMPANY）公共损耗
            if ("COMPANY".equals(x.get("responsibility").asText())
                    && x.get("description").asText().contains("盘点单")) hasCommonOther = true;
        }
        assertTrue(hasDesign && hasQuality && hasCommonOther,
                "复盘应包含设计变更、供应商质量与盘点公共损耗");

        // 10. 预算员采购计划 + 财务汇总
        mvc.perform(get("/api/analytics/procurement-plan")
                        .param("building", "1#楼")
                        .header("Authorization", "Bearer " + token("budget", "budget123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].suggestPurchaseQty").exists());
        JsonNode fin = json(mvc.perform(get("/api/analytics/finance")
                        .header("Authorization", "Bearer " + finance))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
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
