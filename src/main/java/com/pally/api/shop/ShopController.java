package com.pally.api.shop;

import com.pally.api.shop.dto.OpenBoxResponse;
import com.pally.api.shop.dto.StarsResponse;
import com.pally.domain.progress.UserRepository;
import com.pally.domain.progress.UserStats;
import com.pally.domain.progress.usecase.OpenMysteryBoxUseCase;
import com.pally.shared.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/shop")
@RequiredArgsConstructor
public class ShopController {

    private final OpenMysteryBoxUseCase openMysteryBoxUseCase;
    private final UserRepository userRepository;

    @GetMapping("/stars")
    public ResponseEntity<ApiResponse<StarsResponse>> getStars(
            @RequestHeader("X-User-Id") String userId
    ) {
        userRepository.ensureUserExists(userId);
        int stars = userRepository.findById(userId)
                .map(UserStats::stars)
                .orElse(0);
        return ResponseEntity.ok(ApiResponse.success(new StarsResponse(stars)));
    }

    @PostMapping("/open-box")
    public ResponseEntity<ApiResponse<OpenBoxResponse>> openBox(
            @RequestHeader("X-User-Id") String userId
    ) {
        String character = openMysteryBoxUseCase.execute(userId);
        return ResponseEntity.ok(ApiResponse.success(
                new OpenBoxResponse(character, "You unlocked " + character + "!")
        ));
    }
}
