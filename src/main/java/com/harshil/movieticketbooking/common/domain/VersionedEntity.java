package com.harshil.movieticketbooking.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;

/**
 * A {@link BaseEntity} that also carries an optimistic lock version.
 * <p>
 * Seat allocation is made correct by pessimistic row locks, not by this
 * version column - {@code SELECT ... FOR UPDATE} is the authoritative
 * mechanism. The version is a second line of defence for any path that
 * mutates one of these rows <em>without</em> taking the lock first: such an
 * update fails loudly with an optimistic lock exception (surfaced as HTTP 409)
 * instead of silently overwriting a concurrent change.
 */
@MappedSuperclass
public abstract class VersionedEntity extends BaseEntity {

    @Version
    @Column(columnDefinition = "BIGINT", name = "version", nullable = false)
    private long version;

    public long getVersion() {
        return version;
    }
}
