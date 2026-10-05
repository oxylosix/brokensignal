# Broken Signal Companion — протокол и гарантии

Minecraft-мод ↔ клиент мода ↔ Companion ↔ Windows (только окна/звук/папка Companion).

- Сервер шлёт клиенту только смысловой сигнал (`ext`, `cue|arg`). Клиент пересылает его в Companion, только если `companion = true` в `config/brokensignal-client.toml` (по умолчанию выключено).
- Связь: TCP, только `127.0.0.1`, порт и случайный токен — в `%USERPROFILE%\BrokenSignalCompanion\session.txt`. Первая строка клиента: `HELLO <token>`.
- Мод → Companion: `CUE <cue>\t<arg>` (`focus_lost`, `focus_back`, `after_betrayal`, `self_seen`, `while_away`, `test`).
- Companion → мод: `EV <kind>\t<arg>` (закрытый список: `closed`, `typed`, `file_gone`). Сервер чистит и обрезает аргументы до 48 символов.
- Клиент сам измеряет только длительность alt-tab (без информации о других окнах) и сообщает `away <сек>`.

Гарантии: запуск только руками; всегда видимое окно и значок в трее; кнопка «Выключить»; файл `STOP` в папке выключает за ~1 с; файлы — только внутри своей папки; ничего не удаляет; нет сети кроме loopback, нет автозапуска, нет прав администратора, нет чтения чужих файлов, паролей, cookies, истории.
