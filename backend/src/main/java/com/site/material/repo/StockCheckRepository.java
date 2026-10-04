package com.site.material.repo;

import com.site.material.domain.StockCheck;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface StockCheckRepository extends JpaRepository<StockCheck, Long> {

    @Query("select distinct s from StockCheck s left join fetch s.items order by s.createdTime desc")
    List<StockCheck> findAllWithItems();

    @Query("select distinct s from StockCheck s left join fetch s.items where s.id = :id")
    Optional<StockCheck> findByIdWithItems(@Param("id") Long id);

    /** 未关闭盘点单中的盘亏材料（差异关闭前限制继续领用）：[materialId, checkCode] */
    @Query("select i.materialId, s.code from StockCheck s join s.items i " +
            "where s.status = 'COUNTING' and i.actualQty < i.bookQty")
    List<Object[]> findOpenLossMaterials();
}
