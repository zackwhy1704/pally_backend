package com.pally.shared;

import com.pally.shared.exception.BusinessException;
import org.springframework.stereotype.Component;

@Component
public class AdminSecretGuard {

    public void require(String adminSecret) {
        String expected = System.getenv("ADMIN_SECRET");
        if (expected == null || expected.isBlank() || !expected.equals(adminSecret)) {
            throw new BusinessException("Admin access required", 403);
        }
    }
}
