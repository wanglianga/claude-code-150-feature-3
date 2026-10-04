package com.site.material.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.List;

/** 全部请求 DTO 的聚合容器 */
public final class Requests {
    private Requests() {}

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

    public record ItemIn(Long materialId, Long batchId,
                         @NotNull @PositiveOrZero Double applyQty,
                         @PositiveOrZero Double issuedQty,
                         @PositiveOrZero Double qty,
                         String conditionType,
                         Double price) {}

    public record RequisitionRequest(
            @NotNull Long crewId,
            @NotBlank String building,
            String area,
            @NotBlank String process,
            String drawingNo,
            boolean urgent,
            String remark,
            @NotNull List<ItemIn> items) {}

    public record IssueItemIn(@NotNull Long itemId, Long batchId,
                              @NotNull @PositiveOrZero Double issuedQty) {}

    public record IssueRequest(@NotNull List<IssueItemIn> items, String remark) {}

    public record ReturnRequest(
            @NotNull Long crewId,
            Long requisitionId,
            @NotBlank String building,
            String area,
            String process,
            @NotBlank String reasonType,
            String remark,
            @NotNull List<ItemIn> items) {}

    public record WasteRequest(
            @NotNull Long materialId,
            Long batchId,
            Long crewId,
            @NotBlank String building,
            String area,
            String process,
            @NotNull @PositiveOrZero Double qty,
            @NotBlank String wasteType,
            @NotBlank String responsibility,
            Long visaId,
            String description) {}

    public record VisaRequest(
            @NotBlank String building,
            String area, String process, String drawingNo,
            Long materialId, Long crewId,
            @NotBlank String changeType,
            String description,
            @PositiveOrZero Double qtyChange,
            @PositiveOrZero Double unitPrice,
            @PositiveOrZero Double claimAmount) {}

    public record CheckItemIn(@NotNull Long materialId, Long batchId,
                              @NotNull @PositiveOrZero Double actualQty,
                              String responsibility, String remark) {}

    public record StockCheckRequest(String scope, String remark,
                                    @NotNull List<CheckItemIn> items) {}

    /** 盘点差异追查：补录班组领料（夜间未登记领料等） */
    public record BackfillRequest(@NotNull Long crewId,
                                  @NotNull @PositiveOrZero Double qty,
                                  @NotBlank String building,
                                  String area,
                                  @NotBlank String process,
                                  String remark) {}

    /** 盘点差异追查：无法定位责任，转入项目公共损耗 */
    public record CommonLossRequest(@NotNull @PositiveOrZero Double qty,
                                    @NotBlank String building,
                                    String remark) {}

    /** 项目经理确认盘点差异对施工进度的影响 */
    public record ImpactRequest(@NotBlank String impactLevel, String impactNote) {}

    /** 监控交接登记 */
    public record HandoverRequest(@NotBlank String fromKeeper,
                                  @NotBlank String toKeeper,
                                  String cctvRef,
                                  @NotBlank String note,
                                  Long relatedCrewId,
                                  Long relatedMaterialId,
                                  String handoverTime) {}

    /** 班组领料审批限制设置/解除 */
    public record CrewRestrictionRequest(@NotNull Boolean restricted, String reason) {}

    public record InboundRequest(
            @NotNull Long materialId,
            String spec,
            Long supplierId,
            @NotNull @PositiveOrZero Double qtyInbound,
            @PositiveOrZero Double unitPrice,
            String qcStatus,
            String remark) {}

    public record QcRequest(@NotBlank String qcStatus) {}

    public record BudgetRequest(
            @NotBlank String building,
            String area,
            @NotBlank String process,
            String drawingNo,
            @NotNull Long materialId,
            @NotNull @PositiveOrZero Double budgetQty,
            @PositiveOrZero Double budgetPrice,
            Long crewId) {}

    public record MaterialRequest(@NotBlank String name, @NotBlank String category,
                                  String spec, @NotBlank String unit,
                                  Double safetyStock) {}

    public record SupplierRequest(@NotBlank String name, String contact, String phone) {}

    public record CrewRequest(@NotBlank String name, String leader, String trade) {}

    public record GenerateSettlementRequest(String period, Long crewId) {}
}
