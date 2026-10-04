package com.site.material.controller;

import com.site.material.domain.HandoverRecord;
import com.site.material.dto.Requests.HandoverRequest;
import com.site.material.service.HandoverService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/handovers")
public class HandoverController {

    private final HandoverService service;

    public HandoverController(HandoverService service) {
        this.service = service;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return service.list();
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('KEEPER','PM')")
    public HandoverRecord create(@Valid @RequestBody HandoverRequest req) {
        return service.create(req);
    }
}
