package com.pally.shared.exception;

/**
 * Server-side gate failure. Thrown when a free user attempts a premium
 * action (extra tutor, 4th upload, daily chat cap, premium-only surface).
 * GlobalExceptionHandler maps it to HTTP 402 with body shape
 * {@code {data:{code:"UPGRADE_REQUIRED", feature:"..."}, error:..., status:402}}
 * so the Flutter Dio interceptor can route straight to the paywall.
 */
public class UpgradeRequiredException extends PallyException {
    private final String feature;

    public UpgradeRequiredException(String feature) {
        super("Premium plan required to " + describe(feature), 402);
        this.feature = feature;
    }

    public String getFeature() {
        return feature;
    }

    private static String describe(String feature) {
        return switch (feature) {
            case "CREATE_TUTOR" -> "create more tutors";
            case "UPLOAD_DOC" -> "upload more documents";
            case "CHAT_DAILY" -> "chat past the daily free limit";
            case "PARENT_DASHBOARD" -> "open the parent dashboard";
            case "CURRICULUM" -> "follow the curriculum journey";
            case "EXTRA_FREEZE" -> "stack more streak freezes";
            default -> "use this feature";
        };
    }
}
