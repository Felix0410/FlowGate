package com.flowgate.control.controller;

import com.flowgate.contract.Session;
import com.flowgate.control.service.CatalogService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** 四类目录使用相同 CRUD 语义，具体校验在业务服务内，避免重复样板控制器。 */
@RestController
@RequestMapping("/admin")
public class CatalogController {
    private final CatalogService catalog;
    public CatalogController(CatalogService catalog) { this.catalog = catalog; }
    @GetMapping("/{kind:services|apis|routes|policies}")
    public Object list(@PathVariable String kind) { return catalog.list(kind); }
    @PostMapping("/{kind:services|apis|routes|policies}") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public Object create(@PathVariable String kind, @RequestBody Map<String,Object> input, HttpServletRequest req) {
        return catalog.save(kind, null, input, ((Session)req.getAttribute("session")).principalId());
    }
    @PutMapping("/{kind:services|apis|routes|policies}/{id}")
    public Object update(@PathVariable String kind, @PathVariable long id, @RequestBody Map<String,Object> input, HttpServletRequest req) {
        return catalog.save(kind, id, input, ((Session)req.getAttribute("session")).principalId());
    }
    public record Binding(String targetType, Long targetId) {}
    @PostMapping("/policies/{id}/bindings") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public Object bind(@PathVariable long id, @RequestBody Binding input, HttpServletRequest req) {
        return catalog.bind(id, input.targetType(), input.targetId(), ((Session)req.getAttribute("session")).principalId());
    }
}
