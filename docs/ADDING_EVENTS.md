# Adding events

Register authored content from the main mod using Horror Core's API:

```java
HorrorEventRegistry.register(HorrorEvent.builder(
        "theunquiet", "quiet_example", EventCategory.SUBTLE, Rarity.RARE)
    .afterPlayTicks(72_000)
    .cooldown(288_000)
    .when(context -> context.level().isNight())
    .doAction(context -> {
        context.memory().setChainStage(
                context.player().getUUID(), "a_named_chain", "first_consequence");
    })
    .build());
```

Events should be authored as a setup, expectation, interruption, and consequence—not as a random mob spawn. Keep predicates cheap, world queries spatially bounded, and actions server-authoritative. Use chain stages or small state values only when later scenes depend on them.

The player history and chain state APIs have explicit size limits. Event IDs must be unique across loaded content mods.
