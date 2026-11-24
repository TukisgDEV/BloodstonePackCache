package dev.tukisg.packcache;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AppliedPackStateTest {
    private static final List<String> SELECTED = List.of("vanilla", "fabric", "file/local.zip", "bloodstone-preload/000/first");

    @Test void initialLoadMustFinishBeforeResourcesCanBeReused() {
        AppliedPackState state = new AppliedPackState();
        assertFalse(state.matches(SELECTED));
        state.begin(SELECTED);
        assertFalse(state.matches(SELECTED));
        state.complete();
        assertTrue(state.matches(SELECTED));
    }

    @Test void generatedModResourcesDoNotChangeTheLoadedRepositorySelection() {
        AppliedPackState state = new AppliedPackState();
        state.begin(SELECTED);
        var runtimePacks = new ArrayList<>(SELECTED);
        runtimePacks.addFirst("variants-cit:assetgen");
        runtimePacks.addFirst("axiom_translations");
        assertNotEquals(SELECTED, runtimePacks);
        state.complete();
        assertTrue(state.matches(SELECTED));
        assertTrue(state.matches(SELECTED));
    }

    @Test void changedHashAndNewServerPacksRequireReload() {
        AppliedPackState state = new AppliedPackState();
        state.begin(SELECTED);
        state.complete();
        assertFalse(state.matches(List.of("vanilla", "fabric", "file/local.zip", "bloodstone-preload/000/updated")));
        assertFalse(state.matches(List.of("vanilla", "fabric", "file/local.zip", "bloodstone-preload/000/first", "server/bettermodel")));
    }

    @Test void RemovingOrReorderingPacksRequiresReload() {
        AppliedPackState state = new AppliedPackState();
        state.begin(SELECTED);
        state.complete();
        assertFalse(state.matches(List.of("vanilla", "fabric", "file/local.zip")));
        assertFalse(state.matches(List.of("vanilla", "fabric", "bloodstone-preload/000/first", "file/local.zip")));
    }

    @Test void pendingOrFailedReloadCannotBeAcknowledgedAsApplied() {
        AppliedPackState state = new AppliedPackState();
        state.begin(SELECTED);
        state.complete();
        state.begin(SELECTED);
        assertFalse(state.matches(SELECTED));
        state.invalidate();
        state.complete();
        assertFalse(state.matches(SELECTED));
    }

    @Test void selectionChangesDuringLoadingCannotChangeTheLoadedSnapshot() {
        AppliedPackState state = new AppliedPackState();
        var selected = new ArrayList<>(SELECTED);
        state.begin(selected);
        selected.add("server/new-pack");
        state.complete();
        assertTrue(state.matches(SELECTED));
        assertFalse(state.matches(selected));
    }
}
