package com.site.material.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * 盘点明细：盘亏差异按追查结论逐步核销——
 * backfillQty：补录班组领料（如夜间领料未登记）；commonLossQty：无法定位责任转入项目公共损耗。
 * 关闭时按 实盘-当前账面 的剩余差异写入批次盘点调整量。
 */
@Getter
@Setter
@Entity
@Table(name = "stock_check_item")
public class StockCheckItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "check_id")
    private StockCheck stockCheck;

    @Column(nullable = false)
    private Long materialId;

    private Long batchId;

    /** 账面数量 */
    @Column(nullable = false)
    private Double bookQty;

    /** 实盘数量 */
    @Column(nullable = false)
    private Double actualQty;

    /** 单价（核算盘盈盘亏金额） */
    private Double unitPrice = 0d;

    /** 盘亏责任归属，同 WasteRecord.responsibility */
    @Column(length = 16)
    private String responsibility = "COMPANY";

    /** 追查结论：已补录班组领料数量 */
    @Column(nullable = false)
    private Double backfillQty = 0d;

    /** 追查结论：已转入项目公共损耗数量 */
    @Column(nullable = false)
    private Double commonLossQty = 0d;

    /** 补录生成的领料单 id（最近一次） */
    private Long backfillRequisitionId;

    private String remark;

    public double diffQty() {
        return actualQty - bookQty;
    }

    /** 盘亏数量（账 > 实部分，正数） */
    public double lossQty() {
        return Math.max(0d, -diffQty());
    }

    /** 已通过追查结论核销的差异数量 */
    public double resolvedQty() {
        return backfillQty + commonLossQty;
    }

    /** 仍待追查/处理的盘亏数量 */
    public double remainingQty() {
        return Math.max(0d, lossQty() - resolvedQty());
    }
}
