package com.site.material.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 盘点单：账实不符时登记，逐项盘盈/盘亏。
 * 盘亏差异需先追查（入库/领用/退库/损耗/监控交接），按结论补录班组领料或转入项目公共损耗；
 * 差异关闭前相关材料限制继续领用，项目经理确认施工进度影响后方可关闭调账。
 * 状态：COUNTING(盘点/追查中) -> CLOSED(已关闭，账面已调整)
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

    /** 施工进度影响程度：NONE 无影响 / MINOR 轻微 / MAJOR 重大（项目经理确认） */
    @Column(length = 16)
    private String impactLevel;

    @Column(length = 512)
    private String impactNote;

    private String impactConfirmer;
    private LocalDateTime impactConfirmTime;

    @OneToMany(mappedBy = "stockCheck", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<StockCheckItem> items = new ArrayList<>();

    /** 是否存在盘亏（账 > 实）差异项 */
    public boolean hasLoss() {
        return items.stream().anyMatch(i -> i.diffQty() < -1e-9);
    }
}
