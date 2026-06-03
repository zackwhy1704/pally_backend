package com.pally.infrastructure.persistence.knowledge;

import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

/**
 * Composite primary key for {@link WikiPageSourceJpaEntity}.
 */
@Embeddable
public class WikiPageSourceId implements Serializable {

    private String wikiPageId;
    private String knowledgeFileId;

    public WikiPageSourceId() {}

    public WikiPageSourceId(String wikiPageId, String knowledgeFileId) {
        this.wikiPageId = wikiPageId;
        this.knowledgeFileId = knowledgeFileId;
    }

    public String getWikiPageId() { return wikiPageId; }
    public String getKnowledgeFileId() { return knowledgeFileId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WikiPageSourceId that)) return false;
        return Objects.equals(wikiPageId, that.wikiPageId)
                && Objects.equals(knowledgeFileId, that.knowledgeFileId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(wikiPageId, knowledgeFileId);
    }
}
