package com.gena.brokensignal.ext;

import com.gena.brokensignal.HorrorEvents;
import com.gena.brokensignal.HorrorState;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server side of the boundary between the game and the player's desktop.
 *
 * The server never talks to the PC. It only sends a short semantic cue ("after_betrayal",
 * "while_away"...) to the player's own client; the client forwards it to the companion app
 * if, and only if, the player installed it, started it and enabled it in the client config.
 * The companion decides how to present the cue using its own windows, sounds and files in its
 * own folder. Reports come back the same way and are a closed list of kinds.
 *
 * External effects are rare on purpose: at most one every {@link #MIN_GAP} ticks.
 */
public final class Outside {
    public static final long MIN_GAP = 20L * 60 * 15;

    private Outside() {}

    public static boolean available(ServerPlayer p) {
        return HorrorState.of(p).flag("companion");
    }

    /** Send a cue with the given chance if the companion is on and nothing went outside recently. */
    public static boolean maybe(ServerPlayer p, String cue, String arg) {
        HorrorState st = HorrorState.of(p);
        if (!available(p) || st.now() - st.time("ext_last") < MIN_GAP) {
            return false;
        }
        float chance = switch (cue) {
            case "after_betrayal", "self_seen" -> 0.55F;
            case "while_away" -> 0.35F;
            default -> 0.2F;
        };
        return p.getRandom().nextFloat() < chance && send(p, cue, arg) == null;
    }

    public static String send(ServerPlayer p, String cue, String arg) {
        if (!available(p)) {
            return "companion not connected";
        }
        HorrorState st = HorrorState.of(p);
        st.setTime("ext_last", st.now());
        st.inc("ext_sent");
        st.setText("ext_cue", cue);
        return HorrorEvents.meta(p, "ext", cue + "|" + (arg == null ? "" : arg));
    }

    public static void handle(CompanionPayload msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer p) {
                onReport(p, msg.kind(), clean(msg.arg()));
            }
        });
    }

    private static String clean(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == ' ' || c == '_' || c == '.' || c == '-' || c == '?' || c == '!') {
                b.append(c);
            }
            if (b.length() >= 48) {
                break;
            }
        }
        return b.toString().trim();
    }

    /**
     * What the player did outside. Each kind feeds memory; the reaction itself is a scheduled
     * world event (so it happens when the player is back, not instantly).
     */
    static void onReport(ServerPlayer p, String kind, String arg) {
        HorrorState st = HorrorState.of(p);
        long now = st.now();
        switch (kind) {
            case "hello" -> st.setFlag("companion", true);
            case "bye" -> st.setFlag("companion", false);
            case "away" -> {
                // game window lost focus for a while (measured by the client, works without companion)
                int secs = parse(arg);
                st.inc("aways");
                st.setCount("last_away_s", secs);
                if (secs >= 40 && p.getRandom().nextFloat() < 0.25F) {
                    st.schedule("while_away", now + 20L * 2, String.valueOf(secs));
                }
            }
            case "closed" -> {
                // the player closed one of the companion's windows before it finished
                st.inc("ext_closed");
                st.schedule("closed_door", now + 20L * (20 + p.getRandom().nextInt(40)), arg);
            }
            case "typed" -> {
                // the player typed into the companion's notepad; only the first words are kept, in game memory
                if (!arg.isEmpty()) {
                    st.setText("ext_typed", arg);
                    st.schedule("typed_back", now + 20L * 60 * (3 + p.getRandom().nextInt(10)), arg);
                }
            }
            case "file_gone" -> {
                // the player deleted a file the companion made in its own folder
                st.inc("ext_deleted");
                st.schedule("file_gone", now + 20L * (30 + p.getRandom().nextInt(60)), arg);
            }
            default -> { }
        }
    }

    private static int parse(String s) {
        try {
            return Math.max(0, Math.min(86400, Integer.parseInt(s)));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
