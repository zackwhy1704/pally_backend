package com.pally.domain.knowledge.port;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.knowledge.KnowledgeFile;
import com.pally.domain.knowledge.WikiPage;

import java.util.List;

/**
 * Port for compiling knowledge files into structured wiki pages via an AI model.
 */
public interface WikiCompilerPort {

    /**
     * Compiles extracted text from {@code files} into wiki page drafts.
     *
     * @param avatar        the owning avatar (provides name, subject context)
     * @param files         READY knowledge files whose text should be compiled
     * @param existingPages the avatar's current wiki pages (for deduplication / merging)
     * @return a list of {@link WikiPageDraft} objects representing the compiled output
     */
    List<WikiPageDraft> compile(Avatar avatar, List<KnowledgeFile> files, List<WikiPage> existingPages);

    /**
     * Wraps the compiler output with metadata about which tier served the request.
     * Lets callers (and the compile response) surface cost/tier visibility.
     */
    record CompileOutput(List<WikiPageDraft> drafts, String tierServed) {
        public CompileOutput(List<WikiPageDraft> drafts) {
            this(drafts, "unknown");
        }
    }

    /**
     * Compiles with tier visibility. Default implementation delegates to
     * {@link #compile} and wraps in an "unknown" tier.
     */
    default CompileOutput compileWithTier(Avatar avatar, List<KnowledgeFile> files, List<WikiPage> existingPages) {
        return new CompileOutput(compile(avatar, files, existingPages));
    }

    /**
     * Intermediate draft produced by the AI compiler before being persisted as a {@link WikiPage}.
     *
     * @param slug          URL-safe identifier for the page (e.g. "photosynthesis")
     * @param title         human-readable page title
     * @param content       markdown content body
     * @param prerequisites slugs of pages a student must understand first
     */
    record WikiPageDraft(
            String slug, String title, String content, List<String> prerequisites,
            String context
    ) {
        /// Back-compat constructor for callers that don't supply prerequisites.
        public WikiPageDraft(String slug, String title, String content) {
            this(slug, title, content, List.of(), null);
        }
        /// Back-compat constructor for callers that don't supply a context summary.
        public WikiPageDraft(String slug, String title, String content, List<String> prerequisites) {
            this(slug, title, content, prerequisites, null);
        }
    }
}
