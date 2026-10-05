package dev.theunquiet.core.api;

import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceLocation;

public final class HorrorEventRegistry {
    private static final Map<ResourceLocation, HorrorEvent> EVENTS = new ConcurrentHashMap<>();

    private HorrorEventRegistry() {
    }

    public static void register(HorrorEvent event) {
        HorrorEvent previous = EVENTS.putIfAbsent(event.id(), event);
        if (previous != null) {
            throw new IllegalStateException("Duplicate horror event id: " + event.id());
        }
    }

    public static Collection<HorrorEvent> all() {
        return EVENTS.values().stream()
                .sorted(Comparator.comparing(HorrorEvent::id))
                .toList();
    }

    public static HorrorEvent get(ResourceLocation id) {
        return EVENTS.get(id);
    }
}
