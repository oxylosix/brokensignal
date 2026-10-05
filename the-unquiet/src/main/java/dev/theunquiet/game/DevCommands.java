package dev.theunquiet.game;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.theunquiet.UnquietConfig;
import dev.theunquiet.core.api.HorrorEvent;
import dev.theunquiet.core.api.HorrorEventRegistry;
import dev.theunquiet.core.data.HorrorMemory;
import java.util.stream.Collectors;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

public final class DevCommands {
    private DevCommands() {
    }

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("theunquiet")
                .then(Commands.literal("status")
                        .executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            int count = HorrorMemory.get(player.serverLevel().getServer().overworld())
                                    .eventCount(player.getUUID());
                            context.getSource().sendSuccess(
                                    () -> Component.translatable("command.theunquiet.status", count), false);
                            return 1;
                        }))
                .then(Commands.literal("events")
                        .requires(source -> source.hasPermission(2) && UnquietConfig.DEVELOPER_MODE.get())
                        .executes(context -> {
                            String ids = HorrorEventRegistry.all().stream()
                                    .map(HorrorEvent::id)
                                    .map(ResourceLocation::toString)
                                    .collect(Collectors.joining(", "));
                            context.getSource().sendSuccess(() -> Component.literal(ids), false);
                            return 1;
                        }))
                .then(Commands.literal("force")
                        .requires(source -> source.hasPermission(2) && UnquietConfig.DEVELOPER_MODE.get())
                        .then(Commands.argument("event", StringArgumentType.word())
                                .executes(context -> {
                                    ServerPlayer player = context.getSource().getPlayerOrException();
                                    ResourceLocation id = ResourceLocation.tryParse(
                                            StringArgumentType.getString(context, "event"));
                                    if (id == null || !EventDirector.force(player, id)) {
                                        context.getSource().sendFailure(Component.literal("Unknown event ID."));
                                        return 0;
                                    }
                                    context.getSource().sendSuccess(
                                            () -> Component.literal("Forced " + id), false);
                                    return 1;
                                }))));
    }
}
