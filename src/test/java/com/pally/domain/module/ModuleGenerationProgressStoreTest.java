package com.pally.domain.module;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The live progress signal the avatar-status poll was previously blind to during
 * module generation (brainState stays COMPILING and wikiPageCount is already final
 * while modules are still being built per page — see AvatarMapper).
 */
class ModuleGenerationProgressStoreTest {

    @Test
    void find_returnsNull_whenNoBatchStartedForThisAvatar() {
        var store = new ModuleGenerationProgressStore();

        assertThat(store.find("av-unknown")).isNull();
    }

    @Test
    void start_recordsZeroCompleted_andTheGivenTotal() {
        var store = new ModuleGenerationProgressStore();

        store.start("av-1", 6);

        var progress = store.find("av-1");
        assertThat(progress).isNotNull();
        assertThat(progress.completed()).isZero();
        assertThat(progress.total()).isEqualTo(6);
    }

    @Test
    void increment_advancesCompleted_withoutChangingTotal() {
        var store = new ModuleGenerationProgressStore();
        store.start("av-1", 3);

        store.increment("av-1");
        store.increment("av-1");

        var progress = store.find("av-1");
        assertThat(progress.completed()).isEqualTo(2);
        assertThat(progress.total()).isEqualTo(3);
    }

    @Test
    void increment_onUnstartedAvatar_isANoOp_neverFabricatesAnEntry() {
        // A real module-generator failure path could theoretically call increment
        // without a prior start() due to a future refactor bug — must not silently
        // manufacture a "0 of unknown total" entry that would mislead the client.
        var store = new ModuleGenerationProgressStore();

        store.increment("av-never-started");

        assertThat(store.find("av-never-started")).isNull();
    }

    @Test
    void distinctAvatars_trackIndependently() {
        var store = new ModuleGenerationProgressStore();
        store.start("av-1", 5);
        store.start("av-2", 2);

        store.increment("av-1");

        assertThat(store.find("av-1").completed()).isEqualTo(1);
        assertThat(store.find("av-2").completed()).isZero();
        assertThat(store.find("av-2").total()).isEqualTo(2);
    }

    @Test
    void evictExpired_removesEntriesOlderThan30Minutes_keepsRecentOnes() throws Exception {
        var store = new ModuleGenerationProgressStore();
        store.start("av-stale", 4);
        store.start("av-fresh", 4);

        // Backdate the "stale" entry's timestamp past the 30-minute expiry window —
        // reaching into the package-private map directly since the store exposes no
        // clock seam (mirrors CompileJobStore's own untestable-without-reflection design).
        Field progressField = ModuleGenerationProgressStore.class.getDeclaredField("progress");
        progressField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, ModuleGenerationProgressStore.Progress> map =
                (Map<String, ModuleGenerationProgressStore.Progress>) progressField.get(store);
        map.put("av-stale", new ModuleGenerationProgressStore.Progress(
                0, 4, Instant.now().minusSeconds(31 * 60)));

        store.evictExpired();

        assertThat(store.find("av-stale")).isNull();
        assertThat(store.find("av-fresh")).isNotNull();
    }
}
