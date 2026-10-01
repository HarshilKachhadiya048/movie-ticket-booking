package com.harshil.movieticketbooking.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.Hibernate;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.id.uuid.UuidVersion7Strategy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Identity and audit timestamps shared by every persistent entity.
 * <p>
 * <b>Why UUIDv7.</b> A UUID keeps ids non-enumerable and generatable
 * client-side without a database round trip. Plain UUIDv4 pays for that with
 * random insert positions: every new row
 * lands on an arbitrary B-tree page, which fragments the index and bloats the
 * WAL. UUIDv7 embeds a millisecond timestamp in its high bits, so generated
 * ids are monotonically increasing and inserts stay at the right-hand edge of
 * the index the way a sequence would - while remaining a genuine UUID. That
 * matters most for {@code show_seats}, the one table this system writes to
 * under real concurrent pressure.
 * <p>
 * Timestamps are {@link Instant} against {@code TIMESTAMPTZ} columns, so
 * everything stored and compared is an absolute UTC instant. They are
 * populated by Spring Data auditing through the application's {@code Clock}
 * bean, which lets tests advance time without sleeping.
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {

    @Id
    @UuidGenerator(algorithm = UuidVersion7Strategy.class)
    @Column(columnDefinition = "UUID", name = "id", nullable = false, updatable = false)
    private UUID id;

    @CreatedDate
    @Column(columnDefinition = "TIMESTAMPTZ", name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(columnDefinition = "TIMESTAMPTZ", name = "updated_at", nullable = false)
    private Instant updatedAt;

    public UUID getId() {
        return id;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** True until Hibernate has assigned an identifier on first persist. */
    public boolean isNew() {
        return id == null;
    }

    /**
     * Identity is the database id. Entities without one are only equal to
     * themselves, which keeps unsaved instances safe to put in a collection.
     * {@code Hibernate.getClass} unwraps proxies so a lazy reference compares
     * equal to the loaded entity.
     */
    @Override
    public final boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) {
            return false;
        }
        BaseEntity that = (BaseEntity) other;
        return id != null && id.equals(that.id);
    }

    /**
     * Constant hash code. The id is null before persist and non-null after, so
     * an id-derived hash would change while the instance sits in a HashSet.
     */
    @Override
    public final int hashCode() {
        return Hibernate.getClass(this).hashCode();
    }

    @Override
    public String toString() {
        return "%s(id=%s)".formatted(Hibernate.getClass(this).getSimpleName(), id);
    }
}
