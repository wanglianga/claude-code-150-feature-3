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

    /** 盘点追查发现未登记夜间领料后，后续领料需 PM 逐单审批 */
    @Column(nullable = false)
    private boolean requisitionRestricted = false;

    /** 限制原因（如 10 月盘点瓷砖夜料未登记） */
    @Column(length = 256)
    private String restrictReason;

    private String restrictedBy;
    private java.time.LocalDateTime restrictedTime;
}
