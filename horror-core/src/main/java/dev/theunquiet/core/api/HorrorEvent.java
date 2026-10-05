package dev.theunquiet.core.api;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.resources.ResourceLocation;

public record HorrorEvent(
        ResourceLocation id,
        EventCategory category,
        Rarity rarity,
        long minimumPlayTicks,
        long cooldownTicks,
        Predicate<EventContext> condition,
        Consumer<EventContext> action) {

    public HorrorEvent {
        Objects.requireNonNull(id);
        Objects.requireNonNull(category);
        Objects.requireNonNull(rarity);
        Objects.requireNonNull(condition);
        Objects.requireNonNull(action);
        if (minimumPlayTicks < 0 || cooldownTicks < 0) {
            throw new IllegalArgumentException("Event timing values cannot be negative");
        }
    }

    public static Builder builder(String path, EventCategory category, Rarity rarity) {
        return new Builder(ResourceLocation.fromNamespaceAndPath("theunquiet", path), category, rarity);
    }

    public static Builder builder(String namespace, String path, EventCategory category, Rarity rarity) {
        return new Builder(ResourceLocation.fromNamespaceAndPath(namespace, path), category, rarity);
    }

    public static Builder builder(ResourceLocation id, EventCategory category, Rarity rarity) {
        return new Builder(id, category, rarity);
    }

    public static final class Builder {
        private final ResourceLocation id;
        private final EventCategory category;
        private final Rarity rarity;
        private long minimumPlayTicks = 72_000;
        private long cooldownTicks = 72_000;
        private Predicate<EventContext> condition = context -> true;
        private Consumer<EventContext> action = context -> { };

        private Builder(ResourceLocation id, EventCategory category, Rarity rarity) {
            this.id = id;
            this.category = category;
            this.rarity = rarity;
        }

        public Builder afterPlayTicks(long ticks) {
            minimumPlayTicks = ticks;
            return this;
        }

        public Builder cooldown(long ticks) {
            cooldownTicks = ticks;
            return this;
        }

        public Builder when(Predicate<EventContext> predicate) {
            condition = predicate;
            return this;
        }

        public Builder doAction(Consumer<EventContext> consumer) {
            action = consumer;
            return this;
        }

        public HorrorEvent build() {
            return new HorrorEvent(id, category, rarity, minimumPlayTicks, cooldownTicks, condition, action);
        }
    }
}
