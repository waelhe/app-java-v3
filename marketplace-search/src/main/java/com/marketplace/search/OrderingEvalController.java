package com.marketplace.search;

import com.marketplace.shared.api.ApiConstants;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stage 10 (plan D-13, ADR-0006): the ordering eval's read surface — the
 * ADMIN gate (the measured asset is an internal instrument, not a public
 * contract). The report is the deterministic baseline's own evidence: a
 * learned model enters ONLY after it beats these numbers on the same
 * labeled set (the plan's own gate).
 */
@RestController
@RequestMapping(value = ApiConstants.ADMIN + "/search/ordering-eval", version = "1.0")
@PreAuthorize("hasRole('ADMIN')")
public class OrderingEvalController {

    private final OrderingEvalService evalService;

    public OrderingEvalController(OrderingEvalService evalService) {
        this.evalService = evalService;
    }

    @GetMapping
    @Operation(summary = "Score the deterministic ranking on the labeled set (ADMIN)",
            description = "NDCG@10 and MRR for one labeled query, measured through the "
                    + "SAME search pipeline REST and AI callers see — the baseline any "
                    + "future learned ordering must beat.")
    public ResponseEntity<Object> evaluate(@RequestParam String query) {
        return ResponseEntity.ok(evalService.evaluate(query));
    }
}
