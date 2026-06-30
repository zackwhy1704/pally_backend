package com.pally.api.subscription.dto;

import java.util.List;

/**
 * Body for {@code POST /api/v1/subscription/upgrade-link}.
 *
 * @param channels which delivery channels to use — any of {@code "email"},
 *                 {@code "push"}. Null or empty means BOTH. Unknown values are
 *                 ignored by the service.
 */
public record UpgradeLinkRequest(List<String> channels) {
}
