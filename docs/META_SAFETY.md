# Meta feature safety contract

The initial release performs no external computer actions. The client config declares future opt-in controls, all disabled by default, while Safe Mode defaults to enabled.

Any later external feature must satisfy all of these conditions before it is connected to gameplay:

1. Meta Horror, Created Files, and External Windows Effects are independent explicit opt-ins.
2. Safe Mode and an emergency disable always take precedence.
3. Files may only be created inside a dedicated mod-owned directory; no personal files are read, moved, or deleted.
4. Personalization uses only values entered by the player or separately approved metadata, and is off by default.
5. No hidden processes, persistence, system-setting changes, network transmission, or control of other applications.
6. The in-game horror experience remains complete with every meta option disabled.

These gates are a design contract, not a claim that the external layer is already implemented.
