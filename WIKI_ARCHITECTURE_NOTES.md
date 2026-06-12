# Wiki Subsystem — Architecture & Scalability Assessment

_Snapshot assessment. No refactor performed here — this documents risks and
recommendations for a later wave._

## What the wiki is

Each avatar owns a set of markdown `wiki_pages` (slug + title + content +
certainty/quality metadata). Uploads → OCR → Claude/Gemini compile drafts →
`WikiPagePersistenceService.persistDrafts` writes pages, conflict-flags slug
collisions, regenerates flashcards/hint-trees/modules, dedups, and recounts.
Retrieval feeds chat context, quiz generation, and the brain-map UI.

## Indexing (current)

`wiki_pages` indexes:
- `UNIQUE (avatar_id, slug)` — the hot lookup `findByAvatarIdAndSlug` rides this
  composite uniqueness constraint (acts as a covering index for the lookup).
- `idx_wiki_avatar_id (avatar_id)` — `findByAvatarId` / count / sum.
- `idx_wiki_status (status)` — supports the ACTIVE filter.
- `idx_wiki_retrieved (last_retrieved_at)` — staleness archival.

**Verdict:** the per-avatar access pattern is well covered. The single most
common query (`avatar_id + slug`) uses the unique composite. Good baseline.

## Scalability — where it holds up

- **Per-avatar scoping** keeps every query bounded by ONE avatar's page count,
  not the whole table. A child realistically has tens, not thousands, of pages
  per avatar, so most "load all active pages" calls are small in practice.
- **Atomic UPDATE hot paths** (`adjustCertainty`, `recordQuizUsage`,
  `recordRetrieval`, `archive*`) are single set-based `@Modifying` queries
  scoped by `avatar_id [+ slug IN (...)]` — no read-modify-write races, no
  per-row loops. This is the right shape.
- **Compile stays outside the DB transaction** (the 60s AI call is done before
  `persistDrafts` opens its transaction) — avoids long-held locks.

## Scalability — concrete risks

1. **`findByKeywords` is an in-memory full scan.** It loads ALL active pages
   for the avatar into the JVM, then does `O(pages × keywords)` substring
   matching across slug+title+content. Fine at tens of pages; if an avatar's
   corpus grows to hundreds of long pages this becomes a per-chat-message CPU +
   memory cost. _Recommendation:_ move to Postgres full-text search
   (`tsvector` + GIN index) or `pg_trgm` when corpora grow; cap content length
   fed into the scan.

2. **`findPrerequisitesOf` is N+1.** It reads the page, splits its prerequisite
   slugs, then issues one `findByAvatarIdAndSlug` per prerequisite. With a
   handful of prereqs this is trivial; for a densely linked brain map it
   multiplies. _Recommendation:_ a single `WHERE avatar_id = ? AND slug IN (?)`
   batch query.

3. **Dedup is `O(n²)` over content.** `deduplicatePages` runs after every
   compile, comparing every active page pair's tokenized content (Jaccard).
   At tens of pages, negligible; at hundreds it is quadratic in both CPU and
   tokenization allocation, on the compile hot path. _Recommendation:_ gate by
   page count, or bucket by slug-token prefix before pairwise comparison.

4. **Unbounded fetch for context assembly.** `findActiveByAvatarId` and
   `getIndex` return the full active set with no pagination or content cap. The
   brain-map and chat-context paths rely on the corpus staying small.
   _Recommendation:_ paginate the brain-map list endpoint; for chat context
   keep the existing keyword/topic routing as the bound (do not switch to
   "load everything").

5. **Per-compile fan-out.** `persistDrafts` triggers flashcard regen, hint-tree
   gen, and module gen per page, each potentially an AI call, inside the
   persist transaction (best-effort, swallowed). These are correctly isolated
   from rollback, but a large multi-page compile fans out many AI calls
   serially. _Recommendation:_ move regen to an async post-commit queue when
   compile sizes grow.

6. **`conflict_note` (new) is null-only for now.** The column + DTO field exist;
   the extraction that populates it lands later. No risk today — just noting the
   half-wired state so the next wave knows where to plug in.

## Bottom line

The wiki is **solid and appropriately indexed for the current MVP scale**
(small per-avatar corpora). The atomic UPDATE design and per-avatar scoping are
the right foundations. The growth-sensitive spots are all _in-memory whole-
corpus operations_ — keyword search, dedup, prerequisite expansion, and
context assembly — which are quadratic or N+1 and only bite once a single
avatar accumulates hundreds of long pages. None require action now; revisit
items 1–3 first when any avatar's active page count routinely exceeds ~150.
