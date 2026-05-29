# CLAUDE CODE — PALLY (小伴) MASTER IMPLEMENTATION PROMPT
## Full-stack: Flutter mobile app + Java Spring Boot backend + PostgreSQL
## Read this entire file before writing a single line of code.

---

# PART 00 — MANDATORY WORKFLOW (run every time, no exceptions)

After **every** code change, before marking a task complete:
1. Run `./gradlew compileJava` — must succeed with zero warnings.
2. **Write unit tests for every new piece of code** (see the testing rule below).
3. Run `./gradlew test` — all tests must pass.
4. If any of the above fails: fix all issues, then re-run from the beginning.
5. Only report the task as done after every command passes.

## TESTING IS NOT OPTIONAL — for every new piece of code, both apps

Every new piece of code ships with tests. **No exceptions**, no "I'll add tests later",
no "the existing tests cover it". This rule applies to BOTH the Spring Boot backend
and the Flutter app.

- **New use case / service / domain logic** → JUnit + Mockito unit test covering the
  happy path and the named failure paths (insufficient stars, not found, race-loss, etc).
- **New `@RestController` endpoint** → controller-level test (sliced or `@ExtendWith
  (MockitoExtension.class)`) proving 2xx happy path + at least one auth/validation
  failure (401/403/404/400/429 as relevant).
- **New repository method** (`@Query` / `@Modifying`) — integration test via
  Testcontainers when it's an atomic UPDATE or has a non-trivial WHERE clause.
- **New atomic balance / concurrency-sensitive code** → include a concurrency harness
  (ExecutorService + CountDownLatch) that proves the invariant under N parallel callers.
  Money/XP/star mutations MUST have one of these.
- **New Stripe webhook handler branch** → idempotency + signature failure + happy path.
- **New Flyway migration** → at least one test that exercises the schema via the
  repository it backs.
- Aim for the new code itself to be ≥90% covered. The overall repo coverage doesn't
  need to jump 90% in one PR, but the *new* lines you add should be.
- Test names describe the invariant in plain English ("buyStreakFreeze_raceLoss_
  lowStars_reportsNotEnough", not "test1") so a reviewer can read the test list and
  agree with the rules before reading the code.
- **Flutter side** (when working full-stack from this repo): when you add a state
  class, write a state test; when you add a widget, render it in a `ProviderScope`
  with overrides and assert the visible widgets across loading/loaded/error.

**Definition of "done" for any feature change:** code + tests + compile + test suite
all clean. A PR that adds code without tests is not done; it's a draft.

---

# PART 0 — WHO YOU ARE & WHAT YOU'RE BUILDING

You are an expert Flutter + Java engineer implementing Pally (小伴), a kids AI tutor app for ages 6–14. Children create multiple AI tutor avatar characters, each with its own subject-specific knowledge base built from uploaded photos and PDFs. They chat with each avatar to get homework help.

**Figma file:** `https://www.figma.com/design/1hRxGTRdLmTCOyca8vpmdV/tutorly`
**Page:** `Pally · 小伴 — Flutter UI v2`

Before implementing ANY screen, open Figma and inspect the exact frame. Extract all spacing, colours, border radii, and typography values from Figma. Never guess values.

**Mandatory reading before any code:**
- `flutter_coding_practices.md` — Dart rules, widget rules, state management rules
- `flutter_architecture.md` — MVVM, Feature-First structure, shared components

---

# PART 1 — FIGMA SCREEN INVENTORY & FLOW

## Row 1 — Core User Journey (implement first)

```
① Home Screen (01 — Home)
   Entry point. Avatar grid 2-col. Tap avatar → Chat. "+ New" → Character Picker.
   Bottom nav: Home (active), Library, Chat, Me.

② Character Picker (02 — Avatar Showcase)
   12 blind-box characters in 3×4 grid. Tap to select. Name field. Subject chips.
   "Choose [Name]!" CTA → saves avatar, navigates to Add Knowledge.

③ Add Knowledge (02 — Add Knowledge Improved)
   Avatar speech bubble. Amber tip banner: "Only add [Subject] content!".
   3 upload tiles: Camera, PDF, Paste. Wiki brain preview chips at bottom.
   Relevance check fires before any upload completes → may show Screen ⑦.

④ Wiki Compiled (03 — Wiki Compiled 🎉)
   Post-upload success. Confetti. List of new wiki pages learned.
   3 CTAs: Ask tutor now / Quick quiz / View brain.

⑤ Tutor Brain — Wiki Viewer (05 — Zap's Brain 🧠)
   Stats row: pages, topics, sources, links.
   Search bar. Topic list with mastery bars. Recent pages with certainty badges.
   Conflict warning badge on pages with contradictions.
   Accessible from Library nav tab.

⑥ Chat Screen (03 — Chat with Photo Q)
   Avatar in AppBar. Date chip. Message bubbles (user purple, tutor light-purple).
   Tutor messages cite source .md file (small badge).
   Quick reply chips. Photo button (📷) + Gallery button above input.
   Typing indicator (3 animated dots).
   Photo sends → F7 result inline in chat.

⑦ Relevance Warning Dialog (04 — Relevance Check)
   Modal over dimmed background.
   ⚠️ icon circle. Filename chip. Explanation. "Go Back" + "Add Anyway" buttons.
   Triggered automatically when relevance score < 0.45.
```

## Row 2 — Feature Screens (implement after core loop)

```
F1 — Daily Quiz + Spaced Repetition (F1 — Daily Quiz + Spaced Rep)
   Streak + XP header. Question card (MCQ). Green correct / red wrong feedback.
   Source citation on each question. +XP earned display. Next question button.
   Due-today counter at bottom. Accessible via Library tab → "Quiz" button.

F2 — Flashcard Deck (F2 — Auto Flashcard Deck)
   Filter chips: All / Due / Weak / Done. Flashcard flip card (front/back).
   Self-rate: Hard / Okay / Easy → feeds SM-2 algorithm.
   "Up Next" queue. "Auto-generate more" button.
   Accessible from Library tab → "Flashcards" button.

F3 — Progress Dashboard (F3 — Progress Dashboard)
   Level circle + XP bar. Week/Month/All Time tabs.
   Bar chart: minutes per day. Needs Work list with mastery bars.
   "Practice Weak Topics" CTA. Badges row.
   This IS the "Me" tab screen.

F4 — Blind Box Shop (F4 — Blind Box Unlock ✨)
   Star balance. Mystery box visual. "Open Box" button (600⭐).
   Earn methods list. Collection count.
   Accessible from Me tab → "Character Shop".

F5 — Parent Dashboard (F5 — Parent Dashboard 👨‍👩‍👧)
   Child switcher + Add Child. This week stats row.
   Subject breakdown with mastery bars. Alert cards (⚠️ / ✅ / 💡).
   Screen time limit toggle.
   Accessible via Me tab → "Parent Mode" (requires parent PIN).

F6 — AI Study Plan (F6 — AI Study Plan 🗓)
   Avatar speech bubble intro. Today's tasks (Done / Start).
   Coming Up list (day label + task). Test countdown dark card.
   Accessible from Home → "Study Plan" chip, or Library tab.

F7 — Homework Photo Scan Result (F7 — Homework Scan Result 📸)
   Scanned image preview. Blue scan overlay showing question count.
   Expandable answer cards (first expanded by default). XP badge. Follow-up CTA.
   Rendered inline in chat as a special message bubble type.
```

---

# PART 2 — DESIGN TOKEN SYSTEM

## Colours — AppColors

```dart
// lib/core/theme/app_colors.dart
abstract class AppColors {
  static const purple    = Color(0xFF7042ED);
  static const purpleL   = Color(0xFFEBE0FF);
  static const purpleC   = Color(0xFF8F66FA);
  static const teal      = Color(0xFF00BBA4);
  static const tealL     = Color(0xFFD7F7F3);
  static const coral     = Color(0xFFFF6660);
  static const coralL    = Color(0xFFFFE5E4);
  static const amber     = Color(0xFFFFB81A);
  static const amberL    = Color(0xFFFFF5D1);
  static const green     = Color(0xFF2EC870);
  static const greenL    = Color(0xFFDBF9E8);
  static const pink      = Color(0xFFFF6BAE);
  static const pinkL     = Color(0xFFFFE0F0);
  static const gold      = Color(0xFFFFD100);
  static const goldL     = Color(0xFFFFF8CC);
  static const bg        = Color(0xFFFAFAFF);
  static const surface   = Color(0xFFFFFFFF);
  static const surf2     = Color(0xFFF5F2FC);
  static const outline   = Color(0xFFE0DAF0);
  static const text1     = Color(0xFF1F1733);
  static const text2     = Color(0xFF6B618A);
  static const text3     = Color(0xFFA8A0BD);
}
```

## Typography — AppTextStyles (use Google Fonts Nunito)

```dart
// lib/core/theme/app_text_styles.dart
abstract class AppTextStyles {
  static const heading1  = TextStyle(fontFamily:'Nunito', fontSize:22, fontWeight:FontWeight.w800, color:AppColors.text1);
  static const title     = TextStyle(fontFamily:'Nunito', fontSize:18, fontWeight:FontWeight.w700, color:AppColors.text1);
  static const body      = TextStyle(fontFamily:'Nunito', fontSize:14, fontWeight:FontWeight.w400, color:AppColors.text1);
  static const bodySmall = TextStyle(fontFamily:'Nunito', fontSize:12, fontWeight:FontWeight.w400, color:AppColors.text2);
  static const label     = TextStyle(fontFamily:'Nunito', fontSize:11, fontWeight:FontWeight.w600, color:AppColors.text2);
  static const caption   = TextStyle(fontFamily:'Nunito', fontSize:9,  fontWeight:FontWeight.w400, color:AppColors.text3);
}
```

## Spacing — AppSpacing

```dart
abstract class AppSpacing {
  static const double xs = 4;   static const double sm = 8;
  static const double md = 16;  static const double lg = 24;
  static const double xl = 32;  static const double xxl = 48;
  static const screenH = EdgeInsets.symmetric(horizontal:16);
  static const card    = EdgeInsets.all(16);
}
```

---

# PART 3 — FLUTTER PROJECT STRUCTURE

```
lib/
├── app/
│   ├── app.dart              # MaterialApp + ProviderScope
│   ├── router.dart           # GoRouter — all routes typed
│   └── app_theme.dart        # ThemeData using AppColors + Nunito
│
├── core/
│   ├── theme/
│   │   ├── app_colors.dart
│   │   ├── app_text_styles.dart
│   │   └── app_spacing.dart
│   ├── ui/
│   │   ├── pally_button.dart          # filled / outlined / text
│   │   ├── pally_card.dart            # tappable card with shadow
│   │   ├── pally_loading_spinner.dart
│   │   ├── pally_error_card.dart
│   │   ├── pally_bottom_nav.dart      # Material 3 NavigationBar
│   │   ├── pally_avatar_painter.dart  # CustomPainter registry
│   │   └── pally_relevance_dialog.dart
│   └── utils/
│       ├── logger.dart
│       └── extensions.dart
│
├── features/
│   ├── home/                   # ① Home Screen
│   ├── avatar_picker/          # ② Character selection + creation
│   ├── upload/                 # ③ Add knowledge + ④ wiki compiled + ⑦ relevance dialog
│   ├── wiki_viewer/            # ⑤ Tutor brain viewer
│   ├── chat/                   # ⑥ Chat screen + photo scan result (F7)
│   ├── quiz/                   # F1 Daily quiz
│   ├── flashcards/             # F2 Flashcard deck
│   ├── progress/               # F3 Progress dashboard (= "Me" tab)
│   ├── shop/                   # F4 Blind box shop
│   ├── parent/                 # F5 Parent dashboard
│   └── study_plan/             # F6 AI study plan
│
└── shared/
    ├── models/                 # Avatar, WikiPage, ChatMessage, etc.
    └── providers/              # Riverpod providers wiring
```

---

# PART 4 — 4 BOTTOM NAV MODULES (full backbone)

## 🏠 HOME TAB

**Entry:** `HomeScreen` — Avatar grid, streak/XP bar, "+ New" button, Study Plan chip.

**Sub-routes from Home tab:**
- `HomeScreen` → tap avatar → `ChatScreen(avatarId)`
- `HomeScreen` → "+ New" → `AvatarPickerScreen`
- `AvatarPickerScreen` → created → `UploadScreen(avatarId)` (first time)
- `UploadScreen` → upload succeeds → `WikiCompiledScreen(avatarId, newPages)`
- `WikiCompiledScreen` → "Ask now" → `ChatScreen(avatarId)`
- `WikiCompiledScreen` → "Quick quiz" → `QuizScreen(avatarId)`
- `WikiCompiledScreen` → "View brain" → `WikiViewerScreen(avatarId)`
- `ChatScreen` → 📷 tap → `CameraScreen` → returns photo → `HomeworkScanResult` inline

**ViewModel:** `HomeViewModel` — loads avatars, streak, XP level.

---

## 📚 LIBRARY TAB

**Entry:** `LibraryScreen` — shows all avatars as rows. Each row: avatar name, subject, brain stats, 3 action buttons.

**Action buttons per avatar row:**
- `Chat →` → `ChatScreen(avatarId)`
- `Add Content +` → `UploadScreen(avatarId)`
- `Quiz ⚡` → `QuizScreen(avatarId)`

**Sub-routes from Library tab:**
- Tap avatar row → `WikiViewerScreen(avatarId)`
- "Flashcards" chip → `FlashcardScreen(avatarId)`
- "Study Plan" chip → `StudyPlanScreen(avatarId)`

**ViewModel:** `LibraryViewModel` — loads all avatars with stats.

---

## 💬 CHAT TAB

**Entry:** `ChatTabScreen` — if no avatar selected, shows avatar picker grid (same as Home grid but no "+New"). Tap avatar → enters `ChatScreen(avatarId)`.

**This is a navigation shell.** The actual `ChatScreen` is shared between Home and Chat tabs via the same route.

**ChatScreen features:**
- Streaming response from Claude API (SSE)
- Quick reply chips (generated from wiki topics)
- 📷 Photo button → camera → on-device OCR → send to Claude → `HomeworkScanResultBubble`
- 🖼 Gallery button → image picker → same OCR flow
- Source badge on tutor messages
- Typing indicator (animated dots)

**ViewModel:** `ChatViewModel(avatarId)` — manages messages, streaming, wiki context loading.

---

## 👤 ME TAB

**Entry:** `ProgressScreen` = `F3 — Progress Dashboard`
This IS the Me tab. Shows the child's full progress.

**Sub-routes from Me tab:**
- "Practice Weak Topics" → `QuizScreen(weakTopicFilter: true)`
- Badge row → `BadgeDetailScreen` (simple modal)
- "Character Shop ✨" → `ShopScreen` (F4)
- "Parent Mode 🔒" → PIN entry → `ParentDashboardScreen` (F5)
- Settings gear → `SettingsScreen`

**SettingsScreen (simple):**
- Display name
- Notification preferences (daily quiz reminder time)
- Test date setter (feeds Study Plan)
- About / version

---

# PART 5 — CHARACTER PAINTERS

Implement each of the 12 characters as a `CustomPainter`. They must scale cleanly from size 32 (nav badge) to size 120 (shop screen). All coordinates use a `scale` factor based on `size / 60.0`.

**Character list:**
```
Nomi    — pink bunny, asymmetric ears, star patches
Zuzu    — cloud ghost, surprised O-eyes, wispy bottom
Bolt    — green dino, lightning horn, spiky back, toothy grin
Mochi   — beige dumpling bear, tiny bead eyes, chonky
Lumi    — orange fox, sleepy half-lid eyes, glow dot forehead
Quill   — blue alien, huge eyes, mismatched antennae
Fern    — cream mushroom spirit, polka-dot red cap
Cleo    — bandaged mummy cat, one eye peeking
Piko    — cloud + rainbow headband, sparkle X eyes
Fizz    — translucent soda bubble, straw on head, fizzy spots
Tanko   — square tank robot, visor eyes, treads at bottom
Wisp    — yellow flame spirit, teardrop body, sleepy eyes
```

**Usage pattern:**
```dart
class PallyAvatarWidget extends StatelessWidget {
  const PallyAvatarWidget({required this.character, required this.size, super.key});
  final CharacterType character;
  final double size;

  @override
  Widget build(BuildContext context) => CustomPaint(
    size: Size(size, size * 1.15),
    painter: _getPainter(character, size),
  );
}
```

---

# PART 6 — STATE MANAGEMENT (Riverpod 3.x)

All providers use `@riverpod` codegen. Run `dart run build_runner build --delete-conflicting-outputs` after every change.

## Key providers

```dart
// Avatar list
@riverpod
class AvatarListViewModel extends _$AvatarListViewModel {
  @override Future<List<Avatar>> build() => ref.watch(avatarRepositoryProvider).getAll();
  Future<void> createAvatar(String name, CharacterType char, Subject subject) async { ... }
  Future<void> deleteAvatar(String id) async { ... }
}

// Chat (per-avatar, auto-dispose)
@riverpod
class ChatViewModel extends _$ChatViewModel {
  @override ChatState build(String avatarId) { ... }
  Future<void> sendMessage(String text) async { ... }
  Future<void> sendPhotoMessage(File photo) async { ... }
}

// Upload (per-avatar)
@riverpod
class UploadViewModel extends _$UploadViewModel {
  @override UploadState build(String avatarId) => const UploadState.idle();
  Future<void> uploadFile(File file, UploadType type) async { ... } // triggers relevance check first
  Future<void> confirmUploadAnyway(File file) async { ... }
}

// Quiz (per-avatar)
@riverpod
class QuizViewModel extends _$QuizViewModel {
  @override Future<QuizState> build(String avatarId) async { ... }
  void answerQuestion(int answerIndex) { ... }
  void nextQuestion() { ... }
}

// Progress (per-user)
@riverpod
class ProgressViewModel extends _$ProgressViewModel {
  @override Future<ProgressState> build() async { ... }
}

// Shop
@riverpod
class ShopViewModel extends _$ShopViewModel {
  @override ShopState build() => ShopState.initial();
  Future<void> openMysteryBox() async { ... }
}
```

---

# PART 7 — BACKEND API CONTRACT

## Base configuration

```dart
// Inject at build time:
// flutter run --dart-define=API_BASE_URL=https://pallybackend-production.up.railway.app
const baseUrl = String.fromEnvironment('API_BASE_URL', defaultValue:'https://pallybackend-production.up.railway.app');
```

## All endpoints Flutter must call

```
# AVATARS
POST   /api/v1/avatars                              Create avatar
GET    /api/v1/avatars                              List all (for current user)
GET    /api/v1/avatars/{id}                         Get single
DELETE /api/v1/avatars/{id}                         Delete

# KNOWLEDGE / UPLOAD
POST   /api/v1/avatars/{id}/relevance               Check relevance before upload
POST   /api/v1/avatars/{id}/files                   Upload file (multipart)
GET    /api/v1/avatars/{id}/files                   List files
DELETE /api/v1/avatars/{id}/files/{fileId}          Delete file
GET    /api/v1/avatars/{id}/wiki/pages              List wiki pages
GET    /api/v1/avatars/{id}/wiki/pages/{slug}       Get single page content

# CHAT
POST   /api/v1/avatars/{id}/chat  (SSE stream)      Send message, get streaming response
GET    /api/v1/avatars/{id}/chat/history            Load chat history

# QUIZ + FLASHCARDS (generated server-side from wiki)
GET    /api/v1/avatars/{id}/quiz/daily              Get today's quiz questions
POST   /api/v1/avatars/{id}/quiz/answers            Submit answers + update SM-2 schedule
GET    /api/v1/avatars/{id}/flashcards              Get flashcard deck
POST   /api/v1/avatars/{id}/flashcards/{cardId}/rate  Rate card (hard/okay/easy)

# PROGRESS
GET    /api/v1/progress                             Get user progress summary
GET    /api/v1/progress/study-plan                  Get AI-generated study plan

# SHOP
GET    /api/v1/shop/stars                           Get star balance
POST   /api/v1/shop/open-box                        Open mystery box (costs 600 stars)

# AUTH (simple for MVP)
POST   /api/v1/auth/register
POST   /api/v1/auth/login
POST   /api/v1/auth/parent-pin                      Set/verify parent PIN
```

## Key Dart DTO types (@freezed records)

```dart
@freezed class CreateAvatarRequest with _$CreateAvatarRequest {
  const factory CreateAvatarRequest({
    required String name, required Subject subject, required CharacterType characterType,
  }) = _CreateAvatarRequest;
  factory CreateAvatarRequest.fromJson(Map<String,dynamic> j) => _$CreateAvatarRequestFromJson(j);
}

@freezed class RelevanceCheckResponse with _$RelevanceCheckResponse {
  const factory RelevanceCheckResponse({
    required double score, required String reason, required bool isRelevant,
  }) = _RelevanceCheckResponse;
  factory RelevanceCheckResponse.fromJson(Map<String,dynamic> j) => _$RelevanceCheckResponseFromJson(j);
}

@freezed class ChatStreamEvent with _$ChatStreamEvent {
  const factory ChatStreamEvent.token({required String text})                = TokenEvent;
  const factory ChatStreamEvent.done({required String? sourceFile})          = DoneEvent;
  const factory ChatStreamEvent.error({required String message})             = ErrorEvent;
}

@freezed class QuizQuestion with _$QuizQuestion {
  const factory QuizQuestion({
    required String id, required String question, required List<String> options,
    required int correctIndex, required String sourcePageSlug, required String sourceSummary,
  }) = _QuizQuestion;
  factory QuizQuestion.fromJson(Map<String,dynamic> j) => _$QuizQuestionFromJson(j);
}

@freezed class FlashCard with _$FlashCard {
  const factory FlashCard({
    required String id, required String front, required String back,
    required String sourceFile, required CardRating lastRating,
    required DateTime nextReview,
  }) = _FlashCard;
  factory FlashCard.fromJson(Map<String,dynamic> j) => _$FlashCardFromJson(j);
}
```

---

# PART 8 — BACKEND IMPLEMENTATION (Java Spring Boot)

## Stack

- Java 21 · Spring Boot 3.3 · Gradle Kotlin DSL
- PostgreSQL 16 + Flyway migrations
- Spring WebClient for streaming (SSE to Claude)
- JWT auth (stateless) — simple for MVP
- Virtual threads: `spring.threads.virtual.enabled=true`

## Package structure

```
com.pally/
├── PallyApplication.java
├── shared/
│   ├── exception/      # PallyException, AvatarNotFoundException, etc.
│   ├── response/       # ApiResponse<T> record
│   └── util/           # IdGenerator (UUID v7), TextSampler
├── domain/
│   ├── avatar/         # Avatar entity, AvatarRepository port, use cases
│   ├── knowledge/      # KnowledgeFile, WikiPage, ports, use cases
│   ├── chat/           # ChatMessage, ChatRepository, SendMessageUseCase
│   ├── quiz/           # QuizQuestion, FlashCard, SM-2 scheduler
│   └── progress/       # ProgressSummary, StudyPlanGenerator
├── infrastructure/
│   ├── persistence/    # JPA entities + repository adapters
│   ├── ai/             # ClaudeApiClient, ClaudeRelevanceChecker, ClaudeChatProxy,
│   │                   #   ClaudeQuizGenerator, ClaudeStudyPlanGenerator
│   ├── ocr/            # TesseractOcrService, PdfTextExtractor
│   └── storage/        # S3StorageService / LocalStorageService
└── api/
    ├── avatar/         # AvatarController + DTOs
    ├── knowledge/      # KnowledgeController + DTOs
    ├── chat/           # ChatController (SSE) + DTOs
    ├── quiz/           # QuizController + DTOs
    ├── progress/       # ProgressController + DTOs
    ├── shop/           # ShopController + DTOs
    └── auth/           # AuthController + JWT filter
```

## Database schema (Flyway V1)

```sql
CREATE TABLE users (
  id VARCHAR(36) PRIMARY KEY, email VARCHAR(255) UNIQUE NOT NULL,
  display_name VARCHAR(100), parent_pin_hash VARCHAR(100),
  stars INT NOT NULL DEFAULT 0, xp INT NOT NULL DEFAULT 0,
  level INT NOT NULL DEFAULT 1, streak_days INT NOT NULL DEFAULT 0,
  last_active_date DATE, created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE avatars (
  id VARCHAR(36) PRIMARY KEY, user_id VARCHAR(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  name VARCHAR(100) NOT NULL, subject VARCHAR(20) NOT NULL, character_type VARCHAR(20) NOT NULL,
  wiki_page_count INT NOT NULL DEFAULT 0, created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE knowledge_files (
  id VARCHAR(36) PRIMARY KEY, avatar_id VARCHAR(36) NOT NULL REFERENCES avatars(id) ON DELETE CASCADE,
  file_name VARCHAR(255) NOT NULL, storage_key VARCHAR(500) NOT NULL,
  upload_type VARCHAR(20) NOT NULL, page_count INT DEFAULT 0,
  status VARCHAR(20) NOT NULL DEFAULT 'PROCESSING', created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE wiki_pages (
  id VARCHAR(36) PRIMARY KEY, avatar_id VARCHAR(36) NOT NULL REFERENCES avatars(id) ON DELETE CASCADE,
  slug VARCHAR(200) NOT NULL, title VARCHAR(255) NOT NULL, content TEXT NOT NULL,
  certainty VARCHAR(20) NOT NULL DEFAULT 'INFERRED',
  has_conflict BOOLEAN NOT NULL DEFAULT FALSE, updated_at TIMESTAMPTZ NOT NULL,
  UNIQUE (avatar_id, slug)
);

CREATE TABLE chat_messages (
  id VARCHAR(36) PRIMARY KEY, avatar_id VARCHAR(36) NOT NULL REFERENCES avatars(id) ON DELETE CASCADE,
  user_id VARCHAR(36) NOT NULL, role VARCHAR(10) NOT NULL,
  content TEXT NOT NULL, source_file VARCHAR(255), created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE flashcards (
  id VARCHAR(36) PRIMARY KEY, avatar_id VARCHAR(36) NOT NULL REFERENCES avatars(id) ON DELETE CASCADE,
  front TEXT NOT NULL, back TEXT NOT NULL, source_slug VARCHAR(200),
  last_rating VARCHAR(10), next_review_at TIMESTAMPTZ, repetitions INT DEFAULT 0,
  ease_factor REAL DEFAULT 2.5, interval_days INT DEFAULT 1,
  created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE quiz_sessions (
  id VARCHAR(36) PRIMARY KEY, avatar_id VARCHAR(36) NOT NULL,
  user_id VARCHAR(36) NOT NULL, score INT, total INT,
  xp_earned INT DEFAULT 0, completed_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE unlocked_characters (
  user_id VARCHAR(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  character_type VARCHAR(20) NOT NULL, unlocked_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (user_id, character_type)
);

CREATE INDEX idx_avatars_user ON avatars(user_id);
CREATE INDEX idx_wiki_avatar  ON wiki_pages(avatar_id);
CREATE INDEX idx_chat_avatar  ON chat_messages(avatar_id, created_at DESC);
CREATE INDEX idx_flash_avatar ON flashcards(avatar_id, next_review_at);
```

## Claude API — system prompt for chat

```java
String buildSystemPrompt(Avatar avatar, List<WikiPage> wikiContext) {
  String wiki = wikiContext.stream()
    .map(p -> "## " + p.title() + "\n" + p.content())
    .collect(Collectors.joining("\n\n"));

  return """
    You are %s, a friendly and encouraging AI tutor for a child studying %s.
    You explain things simply using examples kids love: food, games, sports, animals.
    Keep sentences short. Use emojis occasionally. Never be condescending.
    ONLY answer questions about %s. For off-topic questions, kindly redirect.

    When you can, ask a Socratic question to guide the child rather than giving the answer directly.

    Your knowledge base (the child's own notes):
    ---
    %s
    ---

    When you reference the knowledge base, end your reply with:
    SOURCE: [page-slug]
    """.formatted(avatar.name(), avatar.subject().label(), avatar.subject().label(), wiki);
}
```

## Relevance check prompt

```java
String buildRelevancePrompt(String avatarSubject, String indexSummary, String sample) {
  return """
    The tutor avatar specialises in: %s

    What the tutor already knows (index summary):
    %s

    New content being uploaded (first 500 tokens):
    %s

    Rate the relevance of the new content to the tutor's subject on a scale of 0.0 to 1.0.
    0.0 = completely unrelated. 1.0 = perfectly on-topic.

    Reply ONLY with valid JSON (no markdown, no explanation):
    {"score": 0.0, "reason": "one sentence"}
    """.formatted(avatarSubject, indexSummary, sample);
}
```

## Quiz generation prompt

```java
String buildQuizPrompt(List<WikiPage> pages) {
  return """
    Based on the following study material, generate 5 multiple-choice quiz questions.
    Each question should test understanding, not just memorisation.
    Questions must come directly from the provided material.

    Material:
    %s

    Reply ONLY with a JSON array:
    [{"question":"...","options":["A...","B...","C...","D..."],"correctIndex":0,"sourcePage":"slug","explanation":"..."}]
    """.formatted(pages.stream().map(p->p.title()+": "+p.content()).collect(Collectors.joining("\n\n")));
}
```

---

# PART 9 — GAMIFICATION & SM-2 SPACED REPETITION

## XP actions (backend awards XP on these events)

```java
enum XpAction {
  COMPLETE_QUIZ(20), STREAK_DAY(10), MASTER_TOPIC(50),
  PHOTO_QUESTION(5), UPLOAD_CONTENT(15), FIRST_CHAT(30);
  final int points;
}
// Level thresholds: 0,100,250,500,900,1400,2000,2800,3800,5000...
// Level = computed from total XP
// Stars = separate from XP. Stars earned = XP earned * 0.5 (rounded)
// Mystery box costs 600 stars
```

## SM-2 Algorithm (Java)

```java
// SuperMemo 2 scheduling — runs in QuizService
public FlashCard applyRating(FlashCard card, CardRating rating) {
  double ef = card.easeFactor();
  int reps = card.repetitions();
  int interval = card.intervalDays();

  int q = switch(rating) { case HARD -> 2; case OKAY -> 4; case EASY -> 5; };
  ef = Math.max(1.3, ef + 0.1 - (5-q) * (0.08 + (5-q) * 0.02));

  if (q < 3) { reps = 0; interval = 1; }
  else if (reps == 0) { interval = 1; reps = 1; }
  else if (reps == 1) { interval = 6; reps = 2; }
  else { interval = (int)Math.round(interval * ef); reps++; }

  return card.withEaseFactor(ef).withRepetitions(reps).withIntervalDays(interval)
             .withNextReviewAt(Instant.now().plus(interval, ChronoUnit.DAYS))
             .withLastRating(rating);
}
```

---

# PART 10 — IMPLEMENTATION ORDER

Build in this exact sequence. Each step is deployable and testable.

## Phase 1 — Core skeleton (Week 1)
1. Flutter project setup: packages, theme, AppColors, AppTextStyles
2. All 12 `CustomPainter` character implementations
3. GoRouter with all typed routes (stubbed screens)
4. `PallyBottomNav` with 4 tabs (stubbed content)
5. `PallyButton`, `PallyCard`, `PallyLoadingSpinner`, `PallyErrorCard`
6. Backend: Spring Boot project, Flyway schema, JWT auth endpoints

## Phase 2 — Avatar creation + upload (Week 2)
7. `HomeScreen` with avatar grid
8. `AvatarPickerScreen` + `CreateAvatarUseCase`
9. `UploadScreen` with camera + PDF + paste options
10. Relevance check API call + `PallyRelevanceDialog`
11. Wiki compilation pipeline (Claude API → markdown → DB)
12. `WikiCompiledScreen` post-upload celebration

## Phase 3 — Chat (Week 3)
13. `ChatScreen` with streaming SSE
14. Message bubble widgets (user, tutor, photo-result)
15. Photo capture → on-device OCR → Claude → `HomeworkScanResultBubble`
16. Quick reply chip generation from wiki topics
17. `WikiViewerScreen` (Library tab entry)

## Phase 4 — Features (Week 4)
18. Daily quiz: generation + `QuizScreen` + SM-2 scheduling
19. `FlashcardScreen` + self-rating + SM-2 integration
20. `ProgressScreen` (Me tab) + charts + badges
21. `ShopScreen` + mystery box animation + star balance
22. `StudyPlanScreen` + test date setting
23. `ParentDashboardScreen` + PIN gate

## Phase 5 — Polish (Week 5)
24. Push notifications (daily quiz reminder)
25. Offline mode (MMKV cache for recent chat + wiki)
26. Onboarding flow (first launch)
27. Settings screen
28. Performance audit + golden tests

---

# PART 11 — CRITICAL RULES (repeat from coding practices)

❌ Never hardcode colours — always `AppColors.*`
❌ Never put logic in `build()` — only UI composition
❌ Never use `Navigator.push` — always `GoRouter.of(context).go()`
❌ Never call `ref.read()` inside `build()` — only in callbacks
❌ Never skip `super.key` on widget constructors
❌ Never create image assets for avatars — use `CustomPainter`
❌ Never use `GetX` or `Provider` package — only Riverpod 3.x
❌ Never hardcode API key — use `--dart-define`
❌ Never put business logic in `@RestController` — use cases only (Java)
❌ Never let JPA entities leave the `infrastructure` layer (Java)
❌ Never use field injection `@Autowired` on fields — constructor injection only (Java)
❌ Never ignore lint warnings — `treat_warnings_as_errors: true`

✅ Run `dart analyze` before every commit
✅ Run `dart run build_runner build` after every `@riverpod` change
✅ Every provider has a unit test with `ProviderScope` overrides
✅ Every screen has a widget test
✅ Backend: every use case has a unit test with Mockito
✅ Backend: every controller has an integration test with Testcontainers

---

# PART 12 — QUICK START COMMANDS

```bash
# Flutter
flutter pub add flutter_riverpod riverpod_annotation go_router drift sqlite3_flutter_libs \
  flutter_secure_storage shared_preferences path_provider file_picker \
  google_mlkit_text_recognition dio image_picker cached_network_image \
  freezed_annotation json_annotation logger uuid intl google_fonts

flutter pub add --dev build_runner riverpod_generator freezed json_serializable drift_dev \
  flutter_test mocktail

dart run build_runner build --delete-conflicting-outputs

# Java / Spring Boot (build.gradle.kts)
implementation("org.springframework.boot:spring-boot-starter-web")
implementation("org.springframework.boot:spring-boot-starter-data-jpa")
implementation("org.springframework.boot:spring-boot-starter-security")
implementation("org.springframework.boot:spring-boot-starter-webflux")  // SSE streaming
implementation("org.flywaydb:flyway-core")
implementation("org.postgresql:postgresql")
implementation("io.jsonwebtoken:jjwt-api:0.12.5")
implementation("net.sourceforge.tess4j:tess4j:5.11.0")    // OCR
implementation("org.apache.pdfbox:pdfbox:3.0.2")          // PDF extraction
implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
testImplementation("org.testcontainers:postgresql")
testImplementation("org.mockito:mockito-core")
```
