# Architecture

The project is a Gradle multi-project build with two NeoForge mods.

## `horror-core`

`HorrorCore` is an installable library mod. Its public API provides:

- immutable event definitions, categories, rarity weights, and a duplicate-checking registry;
- a tick-listener hook so content mods do not need to own global scheduling;
- world-persisted per-player active play time, bounded event history, event counts, cooldown timestamps, small state values, and chain-stage storage.

The Core has no horror content and no client-side effects.

## `the-unquiet`

The main mod registers authored events and one director. The director evaluates candidates only at a slow cadence, after a long warm-up, and uses context predicates, event rarity, cooldowns, and player memory. Actions execute on the logical server and are scoped to the relevant player/world.

External/meta behavior is a separate, disabled-by-default layer. It must not be introduced into event actions directly; it will receive its own consent gate and generated-file boundary.

## Persistence and performance

Player data is stored in one `SavedData` object in the overworld data store. History is capped at 64 event IDs per player. Decision work runs once per in-game minute rather than scanning entities every tick. Entity queries are bounded to the event location.
