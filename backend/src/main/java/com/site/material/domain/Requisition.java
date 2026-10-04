package com.site.material.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 领料单：班组按 施工区域/楼栋/工序/图纸 申请领料。
 * 状态流转：DRAFT -> SUBMITTED -> ISSUED(仓管发料+班长确认) / REJECTED
 * 夜间紧急领料走 URGENT 标记，允许先发后补（仍记录在同一条链路）。
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

    /** DRAFT / SUBMITTED / PENDING_PM(受限班组待项目经理审批) / ISSUED / REJECTED */
    @Column(nullable = false, length = 16)
    private String status = "DRAFT";

    private boolean urgent = false;

    /** 盘点补录单：追查发现班组未登记领料（如夜间领料）时由平台补录 */
    @Column(nullable = false)
    private boolean backfill = false;

    private String applicant;

    /** 班组长确认人 */
    private String confirmedBy;

    private String issuedBy;

    private LocalDateTime applyTime;
    private LocalDateTime issueTime;

    private String remark;

    @OneToMany(mappedBy = "requisition", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<RequisitionItem> items = new ArrayList<>();
}
