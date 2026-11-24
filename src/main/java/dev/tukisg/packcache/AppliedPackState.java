package dev.tukisg.packcache;

import java.util.List;

final class AppliedPackState {
    private List<String> loading;
    private List<String> applied;

    void begin(List<String> selected) {
        loading = List.copyOf(selected);
    }

    void complete() {
        if (loading != null) {
            applied = loading;
            loading = null;
        }
    }

    void invalidate() {
        loading = null;
        applied = null;
    }

    boolean matches(List<String> selected) {
        return loading == null && applied != null && applied.equals(selected);
    }
}
