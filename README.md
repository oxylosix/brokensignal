# Broken Signal — хоррор-мод для NeoForge 1.21.1

Оригинальный хоррор-мод в духе The Broken Script: свой монстр, свои тексты и звуки, без чужих ассетов.

## Что делает мод

| Стадия | Когда | События |
|---|---|---|
| 0 | первые 15 мин (после 3 мин тишины) | шёпот за спиной, шаги сзади, стук, гул, жуткий чат, двери открываются сами |
| 1 | 15–35 мин | **Наблюдатель** стоит вдали и смотрит, фейковый игрок `s1gnal` заходит на сервер, гаснут факелы, обсидиановые столбы |
| 2 | 35–60 мин | красные таблички с надписями, тьма + «ты не один», «ОБЕРНИСЬ» — и он прямо сзади |
| 3 | 60+ мин | ночью: «БЕГИ» — Наблюдатель охотится и бьёт |

**Наблюдатель** исчезает с помехами, если на него долго смотреть, подойти ближе 9 блоков или ударить. Убить его нельзя.

## Команды (нужен OP / читы)

- `/brokensignal trigger <событие>` — whisper, footsteps, knock, chat, door, drone, watcher, join, torches, pillar, sign, darkness, behind, chase
- `/brokensignal stage` — текущая стадия
- `/brokensignal setminutes 70` — перемотать время (70 = стадия 3)

## Настройки

`config/brokensignal-common.toml`: `enabled`, `intensity`, `worldEdits`, `graceMinutes`, `affectCreative`.

## Сборка через GitHub (без Java на ПК)

1. Залей содержимое этой папки (вместе с `.github`) в новый репозиторий.
2. Workflow `build` сам скачает официальный MDK NeoForge 1.21.1, подставит исходники и соберёт мод.
3. Actions → последний запуск → Artifacts → `brokensignal-jar`.

## Сборка локально

1. JDK 21 + MDK: https://github.com/NeoForgeMDKs/MDK-1.21.1-ModDevGradle
2. Замени `src` в MDK на `src` отсюда, строки `mod_*` в `gradle.properties` — на строки из `gradle.properties.mod`.
3. `gradlew build` → `build/libs/brokensignal-1.0.0.jar`.
