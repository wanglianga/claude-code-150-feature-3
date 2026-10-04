package com.site.material.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 监控交接记录：仓库班次交接时登记监控（CCTV）核看到的出入库异常，
 * 是盘点账实差异追查的线索来源（如夜间未登记领料）。
 */
@Getter
@Setter
@Entity
@Table(name = "handover_record")
public class HandoverRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 交接时间 */
    @Column(nullable = false)
    private LocalDateTime handoverTime;

    /** 交班人 */
    @Column(nullable = false, length = 32)
    private String fromKeeper;

    /** 接班人 */
    @Column(nullable = false, length = 32)
    private String toKeeper;

    /** 监控点位/录像时段，如 "A库-西门 CAM-03 23:36-23:52" */
    @Column(length = 128)
    private String cctvRef;

    /** 交接事项/异常说明 */
    @Column(nullable = false, length = 512)
    private String note;

    /** 涉及班组（可空） */
    private Long relatedCrewId;

    /** 涉及材料（可空） */
    private Long relatedMaterialId;

    private String recorder;
    private LocalDateTime createdTime;
}
