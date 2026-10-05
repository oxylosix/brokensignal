# The Unquiet

The Unquiet is a new, original slow-burn horror mod for Minecraft 1.21.1 and NeoForge. Its goal is to let ordinary survival play feel ordinary for long stretches, then make small, context-aware details accumulate into doubt.

The repository contains two installable mods:

- **The Unquiet Horror Core** (`unquietcore`) — reusable event API, rarity model, saved player memory, and server tick hooks.
- **The Unquiet** (`theunquiet`) — the game content and event director; it depends on Horror Core.

Install both jars from the same build. The first playable foundation focuses on pacing, bounded player history, world-aware event selection, and a deliberately small event set. More event families and the consent-driven meta layer are developed on top of this API rather than being mixed into the old BrokenSignal code.

## Safety defaults

External effects, personalized data, and extreme scenes default to **off** in a separate client config; Safe Mode defaults to **on**. No external effects are currently implemented. The mod does not inspect personal files, browser data, credentials, or other applications. Its current event set stays inside Minecraft.

## Build

Requires JDK 21. Run `./gradlew build` on macOS/Linux or `gradlew.bat build` on Windows. GitHub Actions builds both mod jars and publishes them as `the-unquiet-mods`.

## Documentation

- [Architecture](docs/ARCHITECTURE.md)
- [Event API](docs/EVENT_API.md)
- [Meta safety model](docs/META_SAFETY.md)
- [Development and build](docs/DEVELOPMENT.md)
