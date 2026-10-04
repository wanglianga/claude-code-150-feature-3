package com.site.material.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "crew")
public class Crew {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(length = 64)
    private String leader;

    /** 工种：钢筋班 / 泥工班 / 防水班 / 水电班 / 安装班 */
    @Column(length = 64)
    private String trade;

    /** 领料审批限制：盘点补录未登记领料后触发，限制期间领料需项目经理审批 */
    @Column(nullable = false)
    private boolean requisitionRestricted = false;

    @Column(length = 256)
    private String restrictReason;
}
