# Development

## Requirements

- JDK 21
- Gradle wrapper (included)
- NeoForge 21.1 for Minecraft 1.21.1 (resolved by ModDevGradle)

## Build and run

```text
./gradlew build
./gradlew :the-unquiet:runClient
./gradlew :the-unquiet:runServer
```

On Windows, use `gradlew.bat` in place of `./gradlew`.

The build produces separate jars under `horror-core/build/libs` and `the-unquiet/build/libs`. Install both in the same `mods` directory.

## Adding content

Keep authored events in the main mod and reusable scheduling or memory behavior in Horror Core. Add conditions that encode when a scene makes sense, keep rarity meaningful, set an explicit cooldown, and use persistent history only for a clear narrative purpose.

Run `clean build` before submitting changes. Review multiplayer behavior, dedicated-server class loading, save persistence, and the Safe Mode defaults whenever touching shared systems.
