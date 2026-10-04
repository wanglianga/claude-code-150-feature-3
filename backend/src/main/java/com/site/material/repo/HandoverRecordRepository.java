package com.site.material.repo;

import com.site.material.domain.HandoverRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface HandoverRecordRepository extends JpaRepository<HandoverRecord, Long> {
    List<HandoverRecord> findAllByOrderByHandoverTimeDesc();
}
