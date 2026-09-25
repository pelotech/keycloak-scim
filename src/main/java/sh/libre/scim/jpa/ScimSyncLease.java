package sh.libre.scim.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One row per SCIM component, holding the lease for the sync run in
 * progress.
 *
 * <p>A run takes the row under a row lock, renews {@code renewedAt} with a
 * heartbeat, and clears {@code holder} at the end. A row whose {@code
 * renewedAt} is old belongs to a run that died, and the next run takes it.
 *
 * <p>See {@code sh.libre.scim.core.SyncLease} for the rules.
 */
@Entity
@Table(name = "SCIM_SYNC_LEASE")
public class ScimSyncLease {

    @Id
    @Column(name = "COMPONENT_ID", nullable = false)
    private String componentId;

    /** Token of the run that holds the lease, or null when nobody does. */
    @Column(name = "HOLDER")
    private String holder;

    /** Epoch milliseconds when the current holder took the lease, or null. */
    @Column(name = "ACQUIRED_AT")
    private Long acquiredAt;

    /** Epoch milliseconds of the last successful renewal, or null. */
    @Column(name = "RENEWED_AT")
    private Long renewedAt;

    public String getComponentId() {
        return componentId;
    }

    public void setComponentId(String componentId) {
        this.componentId = componentId;
    }

    public String getHolder() {
        return holder;
    }

    public void setHolder(String holder) {
        this.holder = holder;
    }

    public Long getAcquiredAt() {
        return acquiredAt;
    }

    public void setAcquiredAt(Long acquiredAt) {
        this.acquiredAt = acquiredAt;
    }

    public Long getRenewedAt() {
        return renewedAt;
    }

    public void setRenewedAt(Long renewedAt) {
        this.renewedAt = renewedAt;
    }
}
