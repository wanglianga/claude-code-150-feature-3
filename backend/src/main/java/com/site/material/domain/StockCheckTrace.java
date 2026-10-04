package com.site.material.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 盘点差异追查轨迹：按 入库 INBOUND / 领用 REQUISITION / 退库 RETURN / 破损 WASTE /
 * 监控交接 MONITOR 五个方向核查账实差异，每条轨迹登记核查结论与附件说明。
 * 定性动作（公共损耗 COMMON_LOSS、补录夜料 SUPPLEMENT、进度确认 PROGRESS、关闭 CLOSE）也留痕。
 */
@Getter
@Setter
@Entity
@Table(name = "stock_check_trace")
public class StockCheckTrace {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "check_id")
    private StockCheck stockCheck;

    /** INBOUND / REQUISITION / RETURN / WASTE / MONITOR / COMMON_LOSS / SUPPLEMENT / PROGRESS / CLOSE */
    @Column(nullable = false, length = 16)
    private String traceType;

    /** MATCHED 账实相符 / SUSPECT 发现疑点 / CONFIRMED 确认差异 / INFO 说明 */
    @Column(nullable = false, length = 16)
    private String result = "INFO";

    /** 核查的关联材料（可空，表示整单核查） */
    private Long materialId;

    /** 关联单据号（入库批次号/领料单号/退库单号/损耗单号），可空 */
    @Column(length = 64)
    private String refCode;

    /** 涉及数量（如夜料补录数量、调阅监控覆盖的时段出入库量） */
    private Double qty;

    @Column(length = 512)
    private String detail;

    private String recorder;
    private LocalDateTime createdTime;
}
