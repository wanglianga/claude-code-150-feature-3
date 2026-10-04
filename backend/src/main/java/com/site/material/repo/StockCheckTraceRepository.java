package com.site.material.repo;

import com.site.material.domain.StockCheck;
import com.site.material.domain.StockCheckTrace;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StockCheckTraceRepository extends JpaRepository<StockCheckTrace, Long> {

    List<StockCheckTrace> findByStockCheckOrderByCreatedTimeAscIdAsc(StockCheck stockCheck);
}
