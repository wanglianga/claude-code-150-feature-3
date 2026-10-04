package com.site.material.controller;

import com.site.material.domain.Requisition;
import com.site.material.dto.Requests.*;
import com.site.material.service.RequisitionService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/requisitions")
public class RequisitionController {

    private final RequisitionService service;

    public RequisitionController(RequisitionService service) {
        this.service = service;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String status,
                                          @RequestParam(required = false) Long crewId) {
        return service.list(status, crewId);
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable Long id) {
        return service.detail(id);
    }

    /** 发料前核对：施工进度口径的预算量 / 已领量 / 现场库存 / 可用批次 */
    @GetMapping("/{id}/precheck")
    public Map<String, Object> precheck(@PathVariable Long id) {
        return service.preIssueCheck(id);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('FOREMAN','KEEPER','PM')")
    public Requisition create(@Valid @RequestBody RequisitionRequest req) {
        return service.create(req);
    }

    /** 仓管发料（指定批次+实发数量），班组长同步确认领用 */
    @PutMapping("/{id}/issue")
    @PreAuthorize("hasAnyRole('KEEPER','PM')")
    public Map<String, Object> issue(@PathVariable Long id, @Valid @RequestBody IssueRequest req) {
        return service.issue(id, req);
    }

    @PutMapping("/{id}/reject")
    @PreAuthorize("hasAnyRole('KEEPER','PM')")
    public Object reject(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body) {
        return service.reject(id, body == null ? null : body.get("remark"));
    }

    /** 受限班组（盘点夜料未登记）的领料单须项目经理逐单审批 */
    @PutMapping("/{id}/pm-approve")
    @PreAuthorize("hasRole('PM')")
    public Object pmApprove(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body) {
        return service.approve(id, body == null ? null : body.get("remark"));
    }
}
