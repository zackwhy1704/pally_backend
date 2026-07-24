package com.pally.infrastructure.persistence.report;

import com.pally.domain.report.ContentReport;
import com.pally.domain.report.ContentReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class ContentReportRepositoryAdapter implements ContentReportRepository {

    private final ContentReportJpaRepository jpa;

    @Override
    @Transactional
    public ContentReport save(ContentReport r) {
        ContentReportJpaEntity e = new ContentReportJpaEntity();
        e.setId(r.id());
        e.setAvatarId(r.avatarId());
        e.setUserId(r.userId());
        e.setReason(r.reason());
        e.setComment(r.comment());
        e.setMessageText(r.messageText());
        e.setClientMessageId(r.clientMessageId());
        e.setCreatedAt(r.createdAt());
        jpa.save(e);
        return r;
    }
}
