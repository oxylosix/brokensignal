# Configuration

The common config is `config/theunquiet-common.toml`. It controls the in-game event director, intensity, rare/creature/multiplayer categories, audio, extreme events, Safe Mode, and developer commands.

The client-only config is `config/theunquiet-meta-client.toml`. Every external/meta option is off by default, and Safe Mode is on. The current implementation does not execute external effects regardless of these values; these switches are consent gates for future work, not active functionality.

## Developer commands

- `/theunquiet status` reports the number of authored events recorded for the executing player.
- With `developer_mode=true` and operator permission, `/theunquiet events` lists registered event IDs.
- With the same gate, `/theunquiet force <event-id>` executes an event for testing.

Developer commands do not alter world generation or clear player saves.
