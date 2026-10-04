package com.site.material.controller;

import com.site.material.domain.StockCheck;
import com.site.material.dto.Requests.*;
import com.site.material.service.StockCheckService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/stock-checks")
public class StockCheckController {

    private final StockCheckService service;

    public StockCheckController(StockCheckService service) {
        this.service = service;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return service.list();
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('KEEPER','PM')")
    public StockCheck create(@Valid @RequestBody StockCheckRequest req) {
        return service.create(req);
    }

    /** 登记追查轨迹：入库 / 领用 / 退库 / 破损登记 / 监控交接 */
    @PostMapping("/{id}/traces")
    @PreAuthorize("hasAnyRole('KEEPER','PM')")
    public StockCheck addTrace(@PathVariable Long id, @Valid @RequestBody TraceRequest req) {
        return service.addTrace(id, req);
    }

    /** 定性：无法定位责任的差异计入项目公共损耗（核销批次） */
    @PostMapping("/{id}/common-loss")
    @PreAuthorize("hasAnyRole('KEEPER','PM')")
    public Map<String, Object> commonLoss(@PathVariable Long id, @Valid @RequestBody CommonLossRequest req) {
        return service.resolveCommonLoss(id, req);
    }

    /** 定性：发现班组未登记夜间领料，补录领料并加严该班组后续领料审批 */
    @PostMapping("/{id}/supplement-night")
    @PreAuthorize("hasAnyRole('KEEPER','PM')")
    public Map<String, Object> supplementNight(@PathVariable Long id, @Valid @RequestBody SupplementNightRequest req) {
        return service.supplementNight(id, req);
    }

    /** 项目经理确认差异是否影响现场施工进度（关闭前置条件） */
    @PutMapping("/{id}/progress")
    @PreAuthorize("hasRole('PM')")
    public StockCheck progress(@PathVariable Long id, @Valid @RequestBody ProgressConfirmRequest req) {
        return service.confirmProgress(id, req);
    }

    /** 关闭盘点：全部盘亏定性轧平 + PM 已确认进度影响；解除材料领用冻结 */
    @PutMapping("/{id}/close")
    @PreAuthorize("hasAnyRole('KEEPER','PM')")
    public StockCheck close(@PathVariable Long id) {
        return service.close(id);
    }
}
