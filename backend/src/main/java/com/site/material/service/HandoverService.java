package com.site.material.service;

import com.site.material.common.BusinessException;
import com.site.material.domain.Crew;
import com.site.material.domain.HandoverRecord;
import com.site.material.domain.Material;
import com.site.material.dto.Requests.HandoverRequest;
import com.site.material.repo.CrewRepository;
import com.site.material.repo.HandoverRecordRepository;
import com.site.material.repo.MaterialRepository;
import com.site.material.security.SecurityUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 监控交接：仓库班次交接时登记监控核看到的出入库异常，为盘点差异追查提供线索 */
@Service
public class HandoverService {

    private final HandoverRecordRepository handoverRepo;
    private final CrewRepository crewRepo;
    private final MaterialRepository materialRepo;

    public HandoverService(HandoverRecordRepository handoverRepo, CrewRepository crewRepo,
                           MaterialRepository materialRepo) {
        this.handoverRepo = handoverRepo;
        this.crewRepo = crewRepo;
        this.materialRepo = materialRepo;
    }

    @Transactional
    public HandoverRecord create(HandoverRequest req) {
        HandoverRecord h = new HandoverRecord();
        h.setFromKeeper(req.fromKeeper());
        h.setToKeeper(req.toKeeper());
        h.setCctvRef(req.cctvRef());
        h.setNote(req.note());
        h.setRelatedCrewId(req.relatedCrewId());
        h.setRelatedMaterialId(req.relatedMaterialId());
        h.setHandoverTime(parseTime(req.handoverTime()));
        h.setRecorder(SecurityUtil.currentName());
        h.setCreatedTime(LocalDateTime.now());
        return handoverRepo.save(h);
    }

    public List<Map<String, Object>> list() {
        Map<Long, String> crewNames = crewRepo.findAll().stream()
                .collect(Collectors.toMap(Crew::getId, Crew::getName));
        Map<Long, String> matNames = materialRepo.findAll().stream()
                .collect(Collectors.toMap(Material::getId, Material::getName));
        return handoverRepo.findAllByOrderByHandoverTimeDesc().stream().map(h -> {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", h.getId());
            x.put("handoverTime", h.getHandoverTime());
            x.put("fromKeeper", h.getFromKeeper());
            x.put("toKeeper", h.getToKeeper());
            x.put("cctvRef", h.getCctvRef());
            x.put("note", h.getNote());
            x.put("relatedCrewId", h.getRelatedCrewId());
            x.put("relatedCrewName", h.getRelatedCrewId() == null ? null : crewNames.get(h.getRelatedCrewId()));
            x.put("relatedMaterialId", h.getRelatedMaterialId());
            x.put("relatedMaterialName",
                    h.getRelatedMaterialId() == null ? null : matNames.get(h.getRelatedMaterialId()));
            x.put("recorder", h.getRecorder());
            return x;
        }).toList();
    }

    private LocalDateTime parseTime(String s) {
        if (s == null || s.isBlank()) {
            return LocalDateTime.now();
        }
        try {
            return LocalDateTime.parse(s, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (Exception e) {
            throw new BusinessException("交接时间格式非法，应为 yyyy-MM-ddTHH:mm");
        }
    }
}
