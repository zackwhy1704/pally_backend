package com.pally.api.shop;

import com.pally.api.shop.dto.StarsResponse;
import com.pally.domain.progress.UserRepository;
import com.pally.domain.progress.UserStats;
import com.pally.domain.shop.CharacterShopService;
import com.pally.shared.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/shop")
@RequiredArgsConstructor
public class ShopController {

    private final CharacterShopService characterShopService;
    private final UserRepository userRepository;

    @GetMapping("/stars")
    public ResponseEntity<ApiResponse<StarsResponse>> getStars(
            @AuthenticationPrincipal String userId
    ) {
        userRepository.ensureUserExists(userId);
        int stars = userRepository.findById(userId)
                .map(UserStats::stars)
                .orElse(0);
        return ResponseEntity.ok(ApiResponse.success(new StarsResponse(stars)));
    }

    @GetMapping("/characters")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getCharacters(
            @AuthenticationPrincipal String userId
    ) {
        var result = characterShopService.getCharacterUnlocks(userId);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping("/stars/credit")
    public ResponseEntity<ApiResponse<Map<String, Object>>> creditStars(
            @AuthenticationPrincipal String userId,
            @RequestBody Map<String, Integer> request
    ) {
        var result = characterShopService.creditStars(userId, request.getOrDefault("amount", 0));
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping("/open-box")
    public ResponseEntity<ApiResponse<Map<String, Object>>> openBox(
            @AuthenticationPrincipal String userId
    ) {
        var result = characterShopService.openMysteryBox(userId);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /// Spend 150 stars for a streak freeze. The atomic-update guard in
    /// CharacterShopService makes this race-safe at the SQL level — the
    /// audit's D1 lost-update bug is closed for this path.
    @PostMapping("/buy-freeze")
    public ResponseEntity<ApiResponse<Map<String, Object>>> buyFreeze(
            @AuthenticationPrincipal String userId
    ) {
        var result = characterShopService.buyStreakFreeze(userId);
        return ResponseEntity.ok(ApiResponse.success(result));
    }
}
