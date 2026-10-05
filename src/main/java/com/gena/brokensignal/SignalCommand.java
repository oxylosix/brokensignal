package com.gena.brokensignal;

import com.gena.brokensignal.pc.ComputerService;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Developer tools, permission level 2 only. Nothing here is visible to a normal player.
 * /brokensignal trigger|fire|why|list|phase|state|chain|reset|resetevent|pc|stage|setminutes
 */
public final class SignalCommand {
    private SignalCommand() {}

    private static void ok(CommandContext<CommandSourceStack> ctx, String s) {
        ctx.getSource().sendSuccess(() -> Component.literal(s), false);
    }

    private static ServerPlayer me(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return ctx.getSource().getPlayerOrException();
    }

    private static int run(CommandContext<CommandSourceStack> ctx, boolean force) throws CommandSyntaxException {
        ServerPlayer p = me(ctx);
        String id = StringArgumentType.getString(ctx, "event");
        String err = Director.trigger(p, id, force);
        if (err != null) {
            ctx.getSource().sendFailure(Component.literal("Could not run " + id + ": " + err));
            return 0;
        }
        ok(ctx, (force ? "Forced: " : "Fired: ") + id);
        return 1;
    }

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("brokensignal")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("trigger")
                        .then(Commands.argument("event", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(EventCatalog.ids(), b))
                                .executes(ctx -> run(ctx, true))))
                .then(Commands.literal("fire")
                        .then(Commands.argument("event", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(EventCatalog.ids(), b))
                                .executes(ctx -> run(ctx, false))))
                .then(Commands.literal("why")
                        .then(Commands.argument("event", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(EventCatalog.ids(), b))
                                .executes(ctx -> {
                                    String id = StringArgumentType.getString(ctx, "event");
                                    EventDef d = EventCatalog.get(id);
                                    if (d == null) {
                                        ctx.getSource().sendFailure(Component.literal("unknown event: " + id));
                                        return 0;
                                    }
                                    StringBuilder why = new StringBuilder();
                                    boolean allowed = Director.allowed(d, EventCtx.of(me(ctx)), why);
                                    ok(ctx, id + " [" + d.cat + "/" + d.size + "/" + d.rarity + ", phase " + d.minPhase + "-" + d.maxPhase
                                            + "]: " + (allowed ? "allowed now" : "blocked: " + why));
                                    return allowed ? 1 : 0;
                                })))
                .then(Commands.literal("list")
                        .executes(ctx -> {
                            Map<String, List<String>> byCat = new TreeMap<>();
                            for (EventDef d : EventCatalog.all()) {
                                byCat.computeIfAbsent(d.cat + "/" + d.size, k -> new ArrayList<>()).add(d.id);
                            }
                            ok(ctx, EventCatalog.all().size() + " events");
                            byCat.forEach((k, v) -> ok(ctx, k + " (" + v.size() + "): " + String.join(", ", v)));
                            ok(ctx, "chains: " + String.join(", ", Chains.IDS));
                            return 1;
                        }))
                .then(Commands.literal("phase")
                        .executes(ctx -> {
                            ServerPlayer p = me(ctx);
                            HorrorState st = HorrorState.of(p);
                            ok(ctx, "phase " + Director.phase(p) + " (max reached " + st.maxPhase() + ", forced " + st.forcedPhase()
                                    + ", awareness " + st.awareness() + ", tension " + String.format("%.2f", st.tension()) + ")");
                            return Director.phase(p);
                        })
                        .then(Commands.literal("set")
                                .then(Commands.argument("n", IntegerArgumentType.integer(0, 8))
                                        .executes(ctx -> {
                                            HorrorState.of(me(ctx)).setForcedPhase(IntegerArgumentType.getInteger(ctx, "n"));
                                            ok(ctx, "phase forced");
                                            return 1;
                                        })))
                        .then(Commands.literal("clear")
                                .executes(ctx -> {
                                    HorrorState.of(me(ctx)).setForcedPhase(-1);
                                    ok(ctx, "phase no longer forced");
                                    return 1;
                                })))
                .then(Commands.literal("state")
                        .executes(ctx -> {
                            ServerPlayer p = me(ctx);
                            HorrorState st = HorrorState.of(p);
                            long now = st.now();
                            ok(ctx, "next director tick in " + Math.max(0, (st.nextEventAt() - now) / 20) + " s, pace " + st.pace());
                            ok(ctx, "recent: " + String.join(", ", st.history()));
                            ok(ctx, "active chains: " + String.join(", ", st.activeChains()) + " (finished " + st.chainsFinished() + ")");
                            List<String> sch = new ArrayList<>();
                            for (HorrorState.Scheduled s : st.scheduled()) {
                                sch.add(s.id() + "@" + Math.max(0, (s.at() - now) / 20) + "s");
                            }
                            ok(ctx, "scheduled: " + String.join(", ", sch));
                            ok(ctx, "memory: sleeps " + st.count("sleeps") + ", logins " + st.count("logins") + ", pc opens "
                                    + st.count("pc_opens") + ", sightings " + st.count("sightings") + ", night min "
                                    + st.count("min_night") + ", day min " + st.count("min_day") + ", world changes "
                                    + st.count("world_changes"));
                            ok(ctx, "computer open: " + ComputerService.isOpen(p) + ", known pcs " + st.places("pcs").size());
                            return 1;
                        }))
                .then(Commands.literal("chain")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(Chains.IDS, b))
                                .then(Commands.literal("start")
                                        .executes(ctx -> {
                                            ServerPlayer p = me(ctx);
                                            String id = StringArgumentType.getString(ctx, "id");
                                            boolean started = Chains.start(p, HorrorState.of(p), id);
                                            ok(ctx, started ? "chain started: " + id : "chain not started (unknown, active or done): " + id);
                                            return started ? 1 : 0;
                                        }))
                                .then(Commands.literal("reset")
                                        .executes(ctx -> {
                                            String id = StringArgumentType.getString(ctx, "id");
                                            HorrorState.of(me(ctx)).resetChain(id);
                                            ok(ctx, "chain reset: " + id);
                                            return 1;
                                        }))))
                .then(Commands.literal("resetevent")
                        .then(Commands.argument("event", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(EventCatalog.ids(), b))
                                .executes(ctx -> {
                                    String id = StringArgumentType.getString(ctx, "event");
                                    HorrorState.of(me(ctx)).resetEvent(id);
                                    ok(ctx, "event reset: " + id);
                                    return 1;
                                })))
                .then(Commands.literal("reset")
                        .executes(ctx -> {
                            ServerPlayer p = me(ctx);
                            HorrorState.of(p).resetAll();
                            HorrorEvents.setMinutes(p, 0);
                            ok(ctx, "all Broken Signal progress reset for " + p.getGameProfile().getName());
                            return 1;
                        }))
                .then(Commands.literal("pc")
                        .executes(ctx -> {
                            ServerPlayer p = me(ctx);
                            p.getInventory().add(new ItemStack(ModRegistry.COMPUTER_ITEM.get()));
                            ok(ctx, "gave a computer");
                            return 1;
                        }))
                .then(Commands.literal("stage")
                        .executes(ctx -> {
                            ServerPlayer p = me(ctx);
                            int minutes = HorrorEvents.minutes(p);
                            int stage = HorrorEvents.stage(p);
                            ok(ctx, "Stage " + stage + " (" + minutes + " min), phase " + Director.phase(p));
                            return stage;
                        }))
                .then(Commands.literal("setminutes")
                        .then(Commands.argument("minutes", IntegerArgumentType.integer(0, 100000))
                                .executes(ctx -> {
                                    ServerPlayer p = me(ctx);
                                    int minutes = IntegerArgumentType.getInteger(ctx, "minutes");
                                    HorrorEvents.setMinutes(p, minutes);
                                    ok(ctx, "Minutes = " + minutes + ", phase " + Director.phase(p));
                                    return 1;
                                }))));
    }
}
