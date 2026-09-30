package com.harshil.movieticketbooking.refund.service;

import com.harshil.movieticketbooking.common.exception.DomainException;
import com.harshil.movieticketbooking.common.exception.ErrorCode;
import com.harshil.movieticketbooking.refund.domain.RefundPolicy;
import com.harshil.movieticketbooking.refund.domain.RefundPolicyRule;
import com.harshil.movieticketbooking.refund.dto.RefundPolicyRequest;
import com.harshil.movieticketbooking.refund.dto.RefundPolicyResponse;
import com.harshil.movieticketbooking.refund.repository.RefundPolicyRepository;
import com.harshil.movieticketbooking.theater.domain.Theater;
import com.harshil.movieticketbooking.theater.service.TheaterService;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Administration of refund policies.
 * <p>
 * The interesting work here is validating the ladder. Bands are half-open
 * intervals, and a policy whose bands overlap would make the refund for a
 * given cancellation depend on iteration order - the same cancellation could
 * yield 50% or 100% depending on which row the database happened to return
 * first. That is rejected up front rather than discovered when a customer
 * complains.
 * <p>
 * Gaps are allowed but warned about in the response semantics: a cancellation
 * falling in a gap refunds nothing, because
 * {@link RefundCalculator} fails closed.
 */
@Service
@RequiredArgsConstructor
public class RefundPolicyAdminService {

    private final RefundPolicyRepository refundPolicyRepository;
    private final TheaterService theaterService;

    @Transactional(readOnly = true)
    public List<RefundPolicyResponse> listPolicies() {
        return refundPolicyRepository.findAllWithRules()
                .stream()
                .map(RefundPolicyResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public RefundPolicyResponse getPolicy(UUID refundPolicyId) {
        return RefundPolicyResponse.from(requirePolicy(refundPolicyId));
    }

    @Transactional
    public RefundPolicyResponse createPolicy(RefundPolicyRequest request) {
        validateBands(request.rules());
        validateDefaultScope(request);

        if (refundPolicyRepository.existsByNameIgnoreCase(request.name())) {
            throw new DomainException(
                    ErrorCode.REFUND_POLICY_ALREADY_EXISTS,
                    "A refund policy named '%s' already exists".formatted(request.name()));
        }

        Theater theater = resolveTheater(request.theaterId());
        if (theater != null && refundPolicyRepository.existsByTheaterId(theater.getId())) {
            throw new DomainException(
                    ErrorCode.REFUND_POLICY_ALREADY_EXISTS,
                    "%s already has a refund policy".formatted(theater.getName()));
        }

        RefundPolicy policy = RefundPolicy.builder()
                .name(request.name())
                .description(request.description())
                .theater(theater)
                .defaultPolicy(request.defaultPolicyOrFalse())
                .active(request.activeOrDefault())
                .build();
        applyBands(policy, request.rules());

        return RefundPolicyResponse.from(refundPolicyRepository.save(policy));
    }

    /**
     * Replaces the policy's bands wholesale.
     * <p>
     * Whole-ladder replacement rather than per-band editing, because a ladder
     * is only meaningful as a set: adding or removing one band in isolation
     * can silently open a gap or an overlap that neither request would look
     * wrong on its own.
     */
    @Transactional
    public RefundPolicyResponse updatePolicy(UUID refundPolicyId, RefundPolicyRequest request) {
        validateBands(request.rules());
        RefundPolicy policy = requirePolicy(refundPolicyId);

        if (refundPolicyRepository.existsByNameIgnoreCaseAndIdNot(request.name(), refundPolicyId)) {
            throw new DomainException(
                    ErrorCode.REFUND_POLICY_ALREADY_EXISTS,
                    "A refund policy named '%s' already exists".formatted(request.name()));
        }

        policy.update(request.name(), request.description(), request.activeOrDefault());
        applyBands(policy, request.rules());
        return RefundPolicyResponse.from(policy);
    }

    @Transactional
    public void deletePolicy(UUID refundPolicyId) {
        RefundPolicy policy = requirePolicy(refundPolicyId);
        if (policy.isDefaultPolicy()) {
            throw new DomainException(
                    ErrorCode.INVALID_REQUEST,
                    "The default refund policy cannot be deleted; promote another policy first");
        }
        // Issued refunds reference the policy they were assessed under, so
        // deactivate rather than delete once it has been used.
        policy.update(policy.getName(), policy.getDescription(), false);
    }

    // -----------------------------------------------------------------------

    /**
     * Sorted by lower bound on the way in.
     * <p>
     * {@code @OrderBy} on the association only applies when the collection is
     * loaded back from the database, so a policy created in this transaction
     * would otherwise be returned in whatever order the request listed its
     * bands. Sorting here means the in-memory object and the reloaded one
     * always read the same way, and {@code ruleFor} scans a genuinely ordered
     * ladder.
     */
    private void applyBands(RefundPolicy policy, List<RefundPolicyRequest.RefundPolicyRuleRequest> bands) {
        policy.replaceRules(bands.stream()
                .sorted(Comparator.comparingInt(RefundPolicyRequest.RefundPolicyRuleRequest::minHoursBeforeShow))
                .map(band -> RefundPolicyRule.builder()
                        .minHoursBeforeShow(band.minHoursBeforeShow())
                        .maxHoursBeforeShow(band.maxHoursBeforeShow())
                        .refundPercentage(band.refundPercentage())
                        .build())
                .toList());
    }

    /**
     * Rejects overlapping bands.
     * <p>
     * Sorted by lower bound, each band must start no earlier than the previous
     * one ended. An unbounded band is only legal as the last one - anything
     * after it would necessarily overlap.
     */
    private static void validateBands(List<RefundPolicyRequest.RefundPolicyRuleRequest> bands) {
        List<RefundPolicyRequest.RefundPolicyRuleRequest> sorted = bands.stream()
                .sorted(Comparator.comparingInt(RefundPolicyRequest.RefundPolicyRuleRequest::minHoursBeforeShow))
                .toList();

        for (int i = 0; i < sorted.size(); i++) {
            RefundPolicyRequest.RefundPolicyRuleRequest band = sorted.get(i);
            Integer upper = band.maxHoursBeforeShow();

            if (upper != null && upper <= band.minHoursBeforeShow()) {
                throw new DomainException(
                        ErrorCode.INVALID_REQUEST,
                        "Band starting at %dh has maxHoursBeforeShow %d, which is not greater than its minimum"
                                .formatted(band.minHoursBeforeShow(), upper));
            }
            if (i == sorted.size() - 1) {
                continue;
            }
            if (upper == null) {
                throw new DomainException(
                        ErrorCode.REFUND_POLICY_BANDS_OVERLAP,
                        "Only the highest band may be unbounded");
            }
            if (upper > sorted.get(i + 1).minHoursBeforeShow()) {
                throw new DomainException(
                        ErrorCode.REFUND_POLICY_BANDS_OVERLAP,
                        "Bands [%d,%d) and [%d,...) overlap".formatted(
                                band.minHoursBeforeShow(), upper, sorted.get(i + 1).minHoursBeforeShow()));
            }
        }
    }

    private static void validateDefaultScope(RefundPolicyRequest request) {
        if (request.defaultPolicyOrFalse() && request.theaterId() != null) {
            throw new DomainException(
                    ErrorCode.INVALID_REQUEST,
                    "The default refund policy must be global and cannot be scoped to a theater");
        }
    }

    private Theater resolveTheater(UUID theaterId) {
        return theaterId == null ? null : theaterService.requireTheaterWithCity(theaterId);
    }

    private RefundPolicy requirePolicy(UUID refundPolicyId) {
        return refundPolicyRepository.findByIdWithRules(refundPolicyId)
                .orElseThrow(() -> new DomainException(
                        ErrorCode.REFUND_POLICY_NOT_FOUND,
                        "Refund policy %s does not exist".formatted(refundPolicyId)));
    }
}
