package sh.libre.scim.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Single-row lock table used to serialize first-time SCIM group provisioning
 * cluster-wide.
 *
 * <p>Several federated-member import workers can try to provision the same group
 * concurrently (each in its own transaction); without coordination they all see
 * no mapping, all {@code POST /Groups}, and all try to persist a mapping — a
 * duplicate that collides in {@code SCIM_RESOURCE} (rolling back a worker) or, on
 * a non-deduping server, creates two groups. A worker that must provision takes a
 * {@code PESSIMISTIC_WRITE} lock (a {@code SELECT ... FOR UPDATE}) on the seeded
 * row before POSTing, held until its transaction commits. The lock works across
 * cluster nodes (it is in the database), and because it is released only at
 * commit, the next worker sees the winner's committed mapping and skips its POST.
 *
 * <p>See {@code ScimClient.provisionGroupForMembership}.
 */
@Entity
@Table(name = "SCIM_PROVISION_LOCK")
public class ScimProvisionLock {

    /** Id of the single seeded lock row guarding group provisioning. */
    public static final String GROUPS = "groups";

    @Id
    @Column(name = "ID", nullable = false)
    private String id;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }
}
