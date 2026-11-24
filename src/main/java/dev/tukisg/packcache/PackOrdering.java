package dev.tukisg.packcache;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

final class PackOrdering {
    static <T> List<T> arrange(Map<Path, T> preloaded, List<T> requested, Function<T, Path> pathOf) {
        List<T> result = new ArrayList<>(preloaded.values());
        for (T pack : requested) {
            Path path = pathOf.apply(pack);
            if (path == null || !preloaded.containsKey(path)) result.add(pack);
        }
        return result;
    }
}
