package com.site.material.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

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

    /** 账面数量（建单时快照） */
    @Column(nullable = false)
    private Double bookQty;

    /** 实盘数量 */
    @Column(nullable = false)
    private Double actualQty;

    /** 单价（核算盘盈盘亏金额） */
    private Double unitPrice = 0d;

    /** 初始盘亏责任归属，同 WasteRecord.responsibility */
    @Column(length = 16)
    private String responsibility = "COMPANY";

    /** 追查定性：OPEN 未定性 / SUPPLEMENTED 已补录夜料 / COMMON_LOSS 公共损耗 / MIXED 部分补录+部分公共损耗 */
    @Column(nullable = false, length = 16)
    private String resolution = "OPEN";

    /** 夜间未登记领料的责任班组 */
    private Long responsibleCrewId;

    /** 已通过补录领料解释的盘亏数量 */
    @Column(nullable = false)
    private Double supplementedQty = 0d;

    /** 已认定为项目公共损耗的盘亏数量 */
    @Column(nullable = false)
    private Double commonLossQty = 0d;

    /** 补录生成的领料单（最后一张，多张时以轨迹为准） */
    private Long supplementedReqId;

    /** 公共损耗生成的损耗登记单 */
    private Long commonWasteId;

    private String remark;

    public double diffQty() {
        return actualQty - bookQty;
    }

    /** 盘亏数量（正数表示盘亏多少） */
    public double shortageQty() {
        return Math.max(0d, bookQty - actualQty);
    }

    /** 已定性解释的盘亏数量 */
    public double explainedQty() {
        return supplementedQty + commonLossQty;
    }

    /** 尚未解释的盘亏数量 */
    public double unexplainedQty() {
        return shortageQty() - explainedQty();
    }
}
