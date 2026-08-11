package com.pally.api.auth.dto;

public record SocialAuthRequest(
        String idToken,
        String identityToken,
        String authCode,

        /// Affirmative Terms-of-Use acceptance. Only enforced server-side when this
        /// sign-in is about to CREATE a new account (AuthService.signInWithSocial) —
        /// intentionally NOT @AssertTrue here, since that would incorrectly reject a
        /// RETURNING user's login (whose UI never shows a terms checkbox) if the
        /// client omits/defaults it to false.
        boolean acceptedTerms
) {}
