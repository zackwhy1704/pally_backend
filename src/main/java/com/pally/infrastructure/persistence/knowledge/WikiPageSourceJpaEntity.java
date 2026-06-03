package com.pally.infrastructure.persistence.knowledge;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/**
 * JPA entity for the {@code wiki_page_sources} table created in V51.
 *
 * <p>Links each wiki page to the knowledge files that contributed to it
 * during compilation. Attribution is corpus-level (all READY files in a
 * compile run are attributed to every page produced by that run).
 */
@Entity
@Table(name = "wiki_page_sources")
@IdClass(WikiPageSourceId.class)
public class WikiPageSourceJpaEntity {

    @Id
    @Column(name = "wiki_page_id", length = 36, nullable = false)
    private String wikiPageId;

    @Id
    @Column(name = "knowledge_file_id", length = 36, nullable = false)
    private String knowledgeFileId;

    protected WikiPageSourceJpaEntity() {}

    public WikiPageSourceJpaEntity(String wikiPageId, String knowledgeFileId) {
        this.wikiPageId = wikiPageId;
        this.knowledgeFileId = knowledgeFileId;
    }

    public String getWikiPageId() { return wikiPageId; }
    public String getKnowledgeFileId() { return knowledgeFileId; }
}
