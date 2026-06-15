package com.pally.domain.user;

import com.pally.domain.account.AccountType;

import java.util.List;
import java.util.Optional;

public interface UserRepository {

    Optional<User> findById(String userId);
    User save(User user);
    void deleteById(String userId);
    int countByParentId(String parentId);
    List<User> findByParentId(String parentId);
    List<User> findByAccountType(AccountType accountType);
    Optional<User> findByReferralCode(String referralCode);
    void ensureUserExists(String userId);
    int spendStars(String userId, int cost);
    int buyStreakFreeze(String userId, int cost, int cap);
    int earnStreakFreeze(String userId, int cap);
    int consumeStreakFreeze(String userId);
    XpResult addXpAndStars(String userId, int xp, int stars);

    record XpResult(int newXp, int oldLevel, int newLevel,
                    boolean levelledUp, String unlockedRewardLabel) {
        public XpResult(int newXp, int oldLevel, int newLevel, boolean levelledUp) {
            this(newXp, oldLevel, newLevel, levelledUp, null);
        }
        public static XpResult unchanged(int xp, int level) {
            return new XpResult(xp, level, level, false, null);
        }
    }
}
