package com.pally.domain.cost;

/** What kicked off an AI call — the cost driver's shape (from the 0.1 census). */
public enum AiTrigger {
    COMPILE,       // wiki compile / corpus-proportional
    PAGE_UPDATE,   // per-page generation fan-out
    SCREEN_OPEN,   // fired on a screen opening
    USER_ACTION,   // a direct student/teacher action
    SCHEDULED,     // background/cron
    OTHER          // not resolvable at the metering seam
}
