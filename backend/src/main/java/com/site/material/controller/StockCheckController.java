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

    /** 差异追查：入库/领用/退库/损耗/监控交接全量线索 */
    @GetMapping("/{id}/trace")
    public Map<String, Object> trace(@PathVariable Long id) {
        return service.trace(id);
    }

    /** 追查结论：补录班组未登记领料（计入班组成本并限制其后续领料审批） */
    @PostMapping("/{id}/items/{itemId}/backfill")
    @PreAuthorize("hasAnyRole('KEEPER','PM')")
    public Map<String, Object> backfill(@PathVariable Long id, @PathVariable Long itemId,
                                        @Valid @RequestBody BackfillRequest req) {
        return service.backfill(id, itemId, req);
    }

    /** 追查结论：无法定位责任，差异转入项目公共损耗 */
    @PostMapping("/{id}/items/{itemId}/common-loss")
    @PreAuthorize("hasAnyRole('KEEPER','PM')")
    public Map<String, Object> commonLoss(@PathVariable Long id, @PathVariable Long itemId,
                                          @Valid @RequestBody CommonLossRequest req) {
        return service.commonLoss(id, itemId, req);
    }

    /** 项目经理确认差异（限制领用）对施工进度的影响 */
    @PutMapping("/{id}/impact")
    @PreAuthorize("hasRole('PM')")
    public Map<String, Object> confirmImpact(@PathVariable Long id,
                                             @Valid @RequestBody ImpactRequest req) {
        return service.confirmImpact(id, req);
    }

    /** 关闭盘点：差异全部处理且进度影响已确认后调账，解除材料领用限制 */
    @PutMapping("/{id}/close")
    @PreAuthorize("hasAnyRole('KEEPER','PM')")
    public Map<String, Object> close(@PathVariable Long id) {
        return service.close(id);
    }
}
