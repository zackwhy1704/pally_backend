package com.pally.shared.exception;

/**
 * ACCOUNT DELETION Phase 1 (LOCKED policy): an org OWNER tried to delete their
 * account while the centre is NOT empty — it still has classes, enrolled students,
 * or other active staff. Block-unless-empty: the owner must transfer or close the
 * centre first (transfer flows are not built).
 *
 * <p>Mapped by {@code GlobalExceptionHandler} to HTTP 409 with body
 * {@code {data:{code:"CENTRE_NOT_EMPTY"}, error:..., status:409}} so the client can
 * show the "transfer or close your centre first" message rather than a generic 409.
 */
public class CentreNotEmptyException extends PallyException {

    public static final String CODE = "CENTRE_NOT_EMPTY";

    public CentreNotEmptyException() {
        super("Please transfer or close your centre before deleting your account.", 409);
    }
}
