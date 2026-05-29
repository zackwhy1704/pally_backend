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
    private final com.pally.domain.shop.PowerupService powerupService;

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

    @GetMapping("/powerups/catalog")
    public ResponseEntity<ApiResponse<Map<String, Object>>> powerupCatalog() {
        return ResponseEntity.ok(ApiResponse.success(powerupService.catalog()));
    }

    @GetMapping("/powerups")
    public ResponseEntity<ApiResponse<Map<String, Integer>>> powerupInventory(
            @AuthenticationPrincipal String userId
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                powerupService.inventory(userId)));
    }

    /// Buy one powerup of {@code type}. Atomic stars-spend + count upsert
    /// via PowerupService. 400 on insufficient stars or unknown type.
    @PostMapping("/powerups/{type}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> buyPowerup(
            @AuthenticationPrincipal String userId,
            @PathVariable String type
    ) {
        var parsed = com.pally.domain.shop.PowerupService.Type.parse(type);
        return ResponseEntity.ok(ApiResponse.success(
                powerupService.buy(userId, parsed)));
    }

    /// Consume one of {@code type}. 400 if the user has none.
    /// The CLIENT decides when to consume (e.g. opening a quiz hint dialog
    /// → POST /shop/powerups/HINT_TOKEN/consume); we just enforce the count.
    @PostMapping("/powerups/{type}/consume")
    public ResponseEntity<ApiResponse<Map<String, Object>>> consumePowerup(
            @AuthenticationPrincipal String userId,
            @PathVariable String type
    ) {
        var parsed = com.pally.domain.shop.PowerupService.Type.parse(type);
        return ResponseEntity.ok(ApiResponse.success(
                powerupService.consume(userId, parsed)));
    }
}
