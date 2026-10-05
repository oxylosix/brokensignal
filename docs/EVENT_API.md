# Event API

Content mods depend on `horror-core` and register `HorrorEvent` values during mod construction.

```java
HorrorEventRegistry.register(HorrorEvent.builder(
        "quiet_example", EventCategory.SUBTLE, Rarity.RARE)
    .afterPlayTicks(72_000)
    .cooldown(288_000)
    .when(context -> context.level().isNight())
    .doAction(context -> {
        // Keep world queries bounded and make consequences intentional.
    })
    .build());
```

An event has a namespaced ID, category, rarity, minimum active-play threshold, cooldown, context predicate, and action. Registration rejects duplicate IDs. The director records an event only after its action is run.

The context exposes the server level, player, persistent memory, world time, active play ticks, and the world's random source. Do not put blocking I/O, broad world scans, or client-only code in an event action.
