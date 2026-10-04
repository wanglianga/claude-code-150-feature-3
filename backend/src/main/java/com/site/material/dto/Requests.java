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

    /** 盘点差异追查轨迹登记 */
    public record TraceRequest(@NotBlank String traceType, String result, Long materialId,
                        String refCode, @PositiveOrZero Double qty, String detail) {}

    /** 盘亏定性为项目公共损耗（部分或全部） */
    public record CommonLossRequest(@NotNull Long itemId,
                             @NotNull @PositiveOrZero Double qty, String remark) {}

    /** 盘亏定性为班组未登记夜间领料并补录（部分或全部） */
    public record SupplementNightRequest(@NotNull Long itemId,
                                  @NotNull Long crewId,
                                  @NotBlank String building,
                                  String area, String process, String drawingNo,
                                  @NotNull @PositiveOrZero Double qty,
                                  Long batchId, String actualTime, String remark) {}

    /** 项目经理确认盘点差异对现场施工进度的影响 */
    public record ProgressConfirmRequest(@NotBlank String impact, String remark) {
        public boolean affects() { return "YES".equalsIgnoreCase(impact); }
    }
}
