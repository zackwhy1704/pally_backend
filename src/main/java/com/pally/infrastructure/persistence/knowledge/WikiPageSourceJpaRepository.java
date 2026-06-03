package com.pally.infrastructure.persistence.knowledge;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface WikiPageSourceJpaRepository
        extends JpaRepository<WikiPageSourceJpaEntity, WikiPageSourceId> {

    @Modifying
    @Transactional
    @Query("DELETE FROM WikiPageSourceJpaEntity s WHERE s.wikiPageId = :wikiPageId")
    void deleteByWikiPageId(@Param("wikiPageId") String wikiPageId);

    /**
     * Returns the file names of knowledge files that contributed to a wiki page.
     * Joins {@code wiki_page_sources → knowledge_files} in a native query to
     * avoid pulling in the full KnowledgeFile JPA entity hierarchy.
     */
    @Query(value =
           "SELECT kf.file_name " +
           "FROM wiki_page_sources wps " +
           "JOIN knowledge_files kf ON kf.id = wps.knowledge_file_id " +
           "WHERE wps.wiki_page_id = :wikiPageId",
           nativeQuery = true)
    List<String> findSourceFileNamesByWikiPageId(@Param("wikiPageId") String wikiPageId);
}
