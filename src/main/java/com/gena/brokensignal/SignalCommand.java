package com.gena.brokensignal;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** /brokensignal trigger|stage|setminutes (permission level 2). */
public final class SignalCommand {
    private SignalCommand() {}

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("brokensignal")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("trigger")
                        .then(Commands.argument("event", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(HorrorEvents.EVENTS, builder))
                                .executes(ctx -> {
                                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                                    String id = StringArgumentType.getString(ctx, "event");
                                    String err = HorrorEvents.run(p, id, true);
                                    if (err != null) {
                                        ctx.getSource().sendFailure(Component.literal("Could not run " + id + ": " + err));
                                        return 0;
                                    }
                                    ctx.getSource().sendSuccess(() -> Component.literal("Event: " + id), false);
                                    return 1;
                                })))
                .then(Commands.literal("stage")
                        .executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            int minutes = HorrorEvents.minutes(p);
                            int stage = HorrorEvents.stage(p);
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "Stage " + stage + " (" + minutes + " min)"), false);
                            return stage;
                        }))
                .then(Commands.literal("setminutes")
                        .then(Commands.argument("minutes", IntegerArgumentType.integer(0, 100000))
                                .executes(ctx -> {
                                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                                    int minutes = IntegerArgumentType.getInteger(ctx, "minutes");
                                    HorrorEvents.setMinutes(p, minutes);
                                    int stage = HorrorEvents.stage(p);
                                    ctx.getSource().sendSuccess(() -> Component.literal(
                                            "Minutes = " + minutes + ", stage " + stage), false);
                                    return 1;
                                }))));
    }
}
