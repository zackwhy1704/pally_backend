package com.pally.shared.exception;

/**
 * Thrown when a PENDING_CONSENT user attempts an action that requires
 * an ACTIVE account (parent approval or self-consent on 13+).
 *
 * <p>Maps to HTTP 403 with body:
 * {@code {data:{code:"CONSENT_REQUIRED", reason:"..."}, error:..., status:403}}
 * so the Flutter Dio interceptor can show the consent-gate sheet instead
 * of a raw error — mirrors exactly how UpgradeRequiredException works.
 */
public class ConsentRequiredException extends PallyException {

    private final String reason;

    public ConsentRequiredException(String reason) {
        super(describe(reason), 403);
        this.reason = reason;
    }

    public String getReason() { return reason; }

    private static String describe(String reason) {
        return switch (reason) {
            case "UPLOAD"      -> "Upload notes after a grown-up approves your account";
            case "CREATE_TUTOR"-> "Create your own tutor after a grown-up approves";
            case "SHARE_NOTE"  -> "Share notes after a grown-up approves your account";
            case "PERSIST_CHAT"-> "Save your conversations after a grown-up approves";
            case "EARN_XP"     -> "Earn rewards after a grown-up approves your account";
            case "SUBSCRIBE"   -> "Subscribe after a grown-up approves your account";
            case "AI_DATA_TRANSFER" ->
                "Allow Pally to use AI to help answer your questions to continue";
            case "PARENT_LINK_REQUIRED" ->
                "Ask a grown-up to link your account before you can do this";
            default            -> "A grown-up needs to approve your account first";
        };
    }
}
