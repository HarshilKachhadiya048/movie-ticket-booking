package com.harshil.movieticketbooking.refund.controller;

import com.harshil.movieticketbooking.common.api.ApiEndpoints;
import com.harshil.movieticketbooking.refund.dto.RefundPolicyRequest;
import com.harshil.movieticketbooking.refund.dto.RefundPolicyResponse;
import com.harshil.movieticketbooking.refund.service.RefundPolicyAdminService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Refund policy administration - the endpoint that makes the refund ladder
 * configuration rather than code.
 * <p>
 * Updates replace the whole set of bands rather than editing them one at a
 * time: a ladder is only meaningful as a set, and a per-band edit could open a
 * gap or an overlap that no individual request would look wrong on its own.
 * Overlapping bands are rejected with <b>409</b>.
 */
@RestController
@RequiredArgsConstructor
public class AdminRefundPolicyController {

    private final RefundPolicyAdminService refundPolicyAdminService;

    @GetMapping(ApiEndpoints.Admin.RefundPolicies.ROOT)
    public List<RefundPolicyResponse> listPolicies() {
        return refundPolicyAdminService.listPolicies();
    }

    @GetMapping(ApiEndpoints.Admin.RefundPolicies.BY_ID)
    public RefundPolicyResponse getPolicy(@PathVariable UUID refundPolicyId) {
        return refundPolicyAdminService.getPolicy(refundPolicyId);
    }

    @PostMapping(ApiEndpoints.Admin.RefundPolicies.ROOT)
    @ResponseStatus(HttpStatus.CREATED)
    public RefundPolicyResponse createPolicy(@Valid @RequestBody RefundPolicyRequest request) {
        return refundPolicyAdminService.createPolicy(request);
    }

    @PutMapping(ApiEndpoints.Admin.RefundPolicies.BY_ID)
    public RefundPolicyResponse updatePolicy(
            @PathVariable UUID refundPolicyId,
            @Valid @RequestBody RefundPolicyRequest request) {
        return refundPolicyAdminService.updatePolicy(refundPolicyId, request);
    }

    @DeleteMapping(ApiEndpoints.Admin.RefundPolicies.BY_ID)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivatePolicy(@PathVariable UUID refundPolicyId) {
        refundPolicyAdminService.deletePolicy(refundPolicyId);
    }
}
