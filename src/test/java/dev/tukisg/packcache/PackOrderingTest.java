package dev.tukisg.packcache;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PackOrderingTest {
    private record Pack(String id, Path path) {}

    @Test void repeatedMainKeepsAlreadyAppliedIdentityAndOrder() {
        Path main = Path.of("main");
        Path factions = Path.of("factions");
        var preloaded = new LinkedHashMap<Path, Pack>();
        var base = new Pack("preload-main", main);
        var faction = new Pack("preload-factions", factions);
        preloaded.put(main, base);
        preloaded.put(factions, faction);
        assertEquals(List.of(base, faction), PackOrdering.arrange(preloaded,
                List.of(new Pack("server-main", main)), Pack::path));
        assertEquals(List.of(base, faction), PackOrdering.arrange(preloaded,
                List.of(new Pack("server-factions", factions), new Pack("server-main", main)), Pack::path));
    }

    @Test void additionalAndChangedPacksAreNotDiscarded() {
        Path main = Path.of("main");
        var base = new Pack("preload-main", main);
        var model = new Pack("server-model", Path.of("model"));
        var updated = new Pack("updated-main", Path.of("updated"));
        var preloaded = new LinkedHashMap<Path, Pack>();
        preloaded.put(main, base);
        assertEquals(List.of(base, model, updated), PackOrdering.arrange(preloaded, List.of(model, updated), Pack::path));
    }
}
