package com.site.material.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 盘点单：月底账实不符时登记，逐项盘盈/盘亏，并按
 * 入库核对 -> 领用核对 -> 退库核对 -> 破损登记核对 -> 监控交接 的顺序追查差异。
 * 盘亏定性：COMMON_LOSS 项目公共损耗（核销批次、计入公共损耗台账）/
 *           SUPPLEMENTED 班组夜间未登记领料（补录领料单、计入班组成本并加严后续领料审批）。
 * 状态：COUNTING(追查中，差异材料冻结领用) -> CLOSED(已定性、PM 已确认进度影响、轧账关闭)
 */
@Getter
@Setter
@Entity
@Table(name = "stock_check")
public class StockCheck {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32, unique = true)
    private String code;

    @Column(length = 64)
    private String scope;

    /** COUNTING / CLOSED */
    @Column(nullable = false, length = 16)
    private String status = "COUNTING";

    private String recorder;
    private String confirmer;
    private LocalDateTime createdTime;
    private LocalDateTime adjustTime;
    private String remark;

    /** 追查总体结论：COMMON_LOSS 公共损耗 / CREW_NIGHT 班组夜料 / MIXED 混合 / null 未定性 */
    @Column(length = 16)
    private String conclusion;

    /** 进度影响：YES 影响 / NO 不影响 / null 未确认（关闭前必须由 PM 确认） */
    @Column(length = 8)
    private String progressImpact;

    private String progressConfirmedBy;
    private LocalDateTime progressConfirmTime;
    private String progressRemark;

    private String closedBy;
    private LocalDateTime closeTime;

    @OneToMany(mappedBy = "stockCheck", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<StockCheckItem> items = new ArrayList<>();
}
