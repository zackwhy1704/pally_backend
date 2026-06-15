package com.pally.domain.group;

/**
 * Domain port for persisting system-authored posts in a CLASS group's feed.
 *
 * <p>System posts are backend-generated announcements (e.g. "answers released",
 * "muddiest-point alert", "challenge reveal"). The JPA adapter lives in
 * {@code infrastructure/persistence/group}.
 */
public interface GroupSystemPostRepository {

    /**
     * Persists one system post.
     *
     * @param groupId the study-group that owns this post
     * @param kind    a {@code KIND_*} constant (e.g. {@link ClassGroupService#KIND_MUDDIEST})
     * @param body    human-readable announcement text
     * @param refId   optional reference id linking back to the source entity (may be null)
     * @return the generated id of the persisted post
     */
    String save(String groupId, String kind, String body, String refId);
}
