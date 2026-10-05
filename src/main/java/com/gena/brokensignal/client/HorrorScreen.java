package com.gena.brokensignal.client;

import com.gena.brokensignal.ModRegistry;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;

/**
 * Fake "system" screens. They are ordinary in-game screens: the world keeps running and
 * every mode closes by itself after a short, fixed time.
 * LOST: a copy of the vanilla "Connection Lost" screen; the button just closes it.
 * STATIC: a very short noise flash, no text.
 * SAVING: the vanilla "Saving world" message for about a second and a half.
 * TERRAIN: the vanilla "Loading terrain..." screen for two to four seconds.
 */
public class HorrorScreen extends Screen {
    public enum Mode { LOST, STATIC, SAVING, TERRAIN }

    private final Mode mode;
    private final RandomSource rnd = RandomSource.create();
    private final int life;
    private int age;
    private boolean played;

    public HorrorScreen(Mode mode) {
        super(switch (mode) {
            case LOST -> Component.translatable("disconnect.lost");
            case SAVING -> Component.translatable("menu.savingLevel");
            case TERRAIN -> Component.translatable("multiplayer.downloadingTerrain");
            case STATIC -> CommonComponents.EMPTY;
        });
        this.mode = mode;
        this.life = switch (mode) {
            case LOST -> 20 * 120;
            case STATIC -> 8;
            case SAVING -> 30;
            case TERRAIN -> 40 + rnd.nextInt(40);
        };
    }

    @Override
    protected void init() {
        if (mode == Mode.LOST) {
            addRenderableWidget(Button.builder(Component.translatable("gui.toTitle"), b -> onClose())
                    .bounds(width / 2 - 100, height / 2 + 20, 200, 20).build());
        }
    }

    @Override
    public void tick() {
        age++;
        if (mode == Mode.STATIC && !played && minecraft != null) {
            played = true;
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(ModRegistry.STATIC_BURST.get(), 1.0F, 0.6F));
        }
        if (age > life) {
            onClose();
        }
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (mode == Mode.STATIC) {
            g.fill(0, 0, width, height, 0xFF000000);
            return;
        }
        // same as vanilla when there is no level: panorama, blur, menu background
        renderPanorama(g, partialTick);
        renderBlurredBackground(partialTick);
        renderMenuBackground(g);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        switch (mode) {
            case LOST -> {
                g.drawCenteredString(font, title, width / 2, height / 2 - 30, 0xAAAAAA);
                g.drawCenteredString(font, Component.translatable("disconnect.timeout"), width / 2, height / 2 - 6, 0xFFFFFF);
            }
            case SAVING, TERRAIN -> g.drawCenteredString(font, title, width / 2, height / 2 - 50, 0xFFFFFF);
            case STATIC -> {
                for (int y = 0; y < height; y += 2) {
                    for (int x = 0; x < width; x += 2) {
                        int v = rnd.nextInt(256);
                        g.fill(x, y, x + 2, y + 2, 0xFF000000 | v << 16 | v << 8 | v);
                    }
                }
            }
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return mode == Mode.LOST;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
