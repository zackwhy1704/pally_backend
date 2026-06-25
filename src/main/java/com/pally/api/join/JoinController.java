package com.pally.api.join;

import com.pally.domain.join.CodeResolveService;
import com.pally.shared.response.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inbound "Join" surface support. {@code resolve-code} names what a scanned or
 * typed code joins (class / group) so the client can show a confirmation BEFORE
 * committing — the actual joins still go through the existing redeem-class-code /
 * group-join endpoints. Thin controller: delegates to {@link CodeResolveService}.
 */
@RestController
@RequestMapping("/api/v1/join")
public class JoinController {

    private final CodeResolveService codeResolveService;

    public JoinController(CodeResolveService codeResolveService) {
        this.codeResolveService = codeResolveService;
    }

    @GetMapping("/resolve-code/{code}")
    public ResponseEntity<ApiResponse<CodeResolveService.ResolvedCode>> resolveCode(
            @AuthenticationPrincipal String userId,
            @PathVariable String code) {
        return ResponseEntity.ok(ApiResponse.success(codeResolveService.resolve(code)));
    }
}
