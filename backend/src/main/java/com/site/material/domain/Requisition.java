package com.site.material.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 领料单：班组按 施工区域/楼栋/工序/图纸 申请领料。
 * 状态流转：DRAFT -> SUBMITTED -> PENDING_PM(班组受限需PM审批) -> ISSUED(仓管发料+班长确认) / REJECTED
 * 夜间紧急领料走 URGENT 标记，允许先发后补（仍记录在同一条链路）。
 * 盘点补录的夜间未登记领料走 backdated=true，直接 ISSUED 并标记来源盘点单。
 */
@Getter
@Setter
@Entity
@Table(name = "requisition")
public class Requisition {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32, unique = true)
    private String code;

    @Column(nullable = false)
    private Long crewId;

    @Column(nullable = false, length = 32)
    private String building;

    @Column(length = 64)
    private String area;

    @Column(nullable = false, length = 64)
    private String process;

    @Column(length = 64)
    private String drawingNo;

    /** DRAFT / SUBMITTED / PENDING_PM / ISSUED / REJECTED */
    @Column(nullable = false, length = 16)
    private String status = "DRAFT";

    private boolean urgent = false;

    /** 盘点追查补录的夜间领料（先发后补、事后建单） */
    @Column(nullable = false)
    private boolean backdated = false;

    /** 补录来源盘点单 */
    private Long sourceCheckId;

    private String applicant;

    /** 班组长确认人 */
    private String confirmedBy;

    private String issuedBy;

    /** PENDING_PM 单的项目经理审批人 */
    private String pmApprovedBy;
    private LocalDateTime pmApproveTime;

    private LocalDateTime applyTime;
    private LocalDateTime issueTime;

    /** 补录时还原的实际领料时间（夜间） */
    private LocalDateTime actualUseTime;

    private String remark;

    @OneToMany(mappedBy = "requisition", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<RequisitionItem> items = new ArrayList<>();
}
