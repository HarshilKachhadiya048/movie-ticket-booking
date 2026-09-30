package com.harshil.movieticketbooking.refund.domain;

import com.harshil.movieticketbooking.common.domain.BaseEntity;
import com.harshil.movieticketbooking.theater.domain.Theater;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A named, configurable refund ladder.
 * <p>
 * Either attached to one theater or flagged as the single platform default.
 * {@code RefundService} prefers the show's theater policy and falls back to
 * the default, so a venue can run its own terms without code changes. Both
 * "one policy per theater" and "exactly one default" are enforced by partial
 * unique indexes rather than by application checks.
 */
@Entity
@Getter
@Builder
@Table(name = "refund_policies")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class RefundPolicy extends BaseEntity {

    @Column(columnDefinition = "VARCHAR(120)", name = "name", nullable = false, unique = true, length = 120)
    private String name;

    @Column(columnDefinition = "VARCHAR(255)", name = "description", length = 255)
    private String description;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            columnDefinition = "UUID",
            name = "theater_id",
            foreignKey = @ForeignKey(name = "fk_refund_policies_theater"))
    private Theater theater;

    @Column(columnDefinition = "BOOLEAN", name = "is_default", nullable = false)
    private boolean defaultPolicy;

    @Column(columnDefinition = "BOOLEAN", name = "active", nullable = false)
    private boolean active;

    @Builder.Default
    @OrderBy("minHoursBeforeShow ASC")
    @OneToMany(mappedBy = "refundPolicy", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<RefundPolicyRule> rules = new ArrayList<>();

    public List<RefundPolicyRule> getRules() {
        return Collections.unmodifiableList(rules);
    }

    public void addRule(RefundPolicyRule rule) {
        rules.add(rule);
        rule.attachTo(this);
    }

    public void replaceRules(List<RefundPolicyRule> newRules) {
        rules.clear();
        newRules.forEach(this::addRule);
    }

    /**
     * The band covering {@code hoursBeforeShow}, if the policy defines one.
     * <p>
     * An empty result is treated by the caller as "no refund", so a policy with
     * a gap fails closed rather than silently refunding the full amount.
     */
    public Optional<RefundPolicyRule> ruleFor(BigDecimal hoursBeforeShow) {
        return rules.stream()
                .filter(rule -> rule.matches(hoursBeforeShow))
                .findFirst();
    }

    public void update(String name, String description, boolean active) {
        this.name = name;
        this.description = description;
        this.active = active;
    }
}
