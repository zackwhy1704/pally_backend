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
     * Returns knowledge_file_ids that already have at least one provenance entry
     * (i.e. they've been successfully compiled into wiki pages before). Used for
     * incremental compile — these files can be skipped.
     */
    @Query(value =
           "SELECT DISTINCT wps.knowledge_file_id " +
           "FROM wiki_page_sources wps " +
           "JOIN knowledge_files kf ON kf.id = wps.knowledge_file_id " +
           "WHERE kf.avatar_id = :avatarId AND kf.status = 'READY'",
           nativeQuery = true)
    List<String> findCompiledFileIdsByAvatarId(@Param("avatarId") String avatarId);

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

    /**
     * Returns the slugs of all ACTIVE wiki pages that were produced by any of
     * the given knowledge file IDs. Used in incremental compile to compute the
     * full surviving-slug set: previously-compiled READY files still own their
     * wiki pages even though the current run only processes new files.
     */
    @Query(value =
           "SELECT wp.slug " +
           "FROM wiki_page_sources wps " +
           "JOIN wiki_pages wp ON wp.id = wps.wiki_page_id " +
           "WHERE wps.knowledge_file_id IN :fileIds AND wp.status = 'ACTIVE'",
           nativeQuery = true)
    List<String> findActiveSlugsByFileIds(@Param("fileIds") List<String> fileIds);
}
