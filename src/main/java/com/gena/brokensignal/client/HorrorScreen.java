package com.gena.brokensignal.client;

import com.gena.brokensignal.ModRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;

/** Fake full-screen effects: "Connection Lost", TV static and a jumpscare. */
public class HorrorScreen extends Screen {
    public enum Mode { LOST, STATIC, JUMPSCARE }

    private final Mode mode;
    private final String text;
    private final RandomSource rnd = RandomSource.create();
    private int age;
    private int clicks;
    private boolean played;
    private Component reason = Component.literal("s1gnal: ты не можешь уйти");
    private Button button;

    public HorrorScreen(Mode mode, String text) {
        super(Component.empty());
        this.mode = mode;
        this.text = text == null ? "" : text;
    }

    @Override
    protected void init() {
        if (mode == Mode.LOST) {
            button = addRenderableWidget(Button.builder(Component.translatable("gui.toMenu"), b -> click())
                    .bounds(width / 2 - 100, height / 2 + 30, 200, 20).build());
        }
        if (!played && mode != Mode.LOST) {
            played = true;
            Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(
                    ModRegistry.STATIC_BURST.get(), mode == Mode.JUMPSCARE ? 0.6F : 1.0F, 1.0F));
        }
    }

    private void click() {
        clicks++;
        if (clicks == 1) {
            reason = Component.literal("нет.");
        } else if (clicks == 2) {
            reason = Component.literal("я сказал нет.");
            if (button != null) {
                button.setMessage(Component.literal("не уходи"));
            }
        } else {
            onClose();
        }
    }

    @Override
    public void tick() {
        age++;
        int max = switch (mode) {
            case LOST -> 20 * 20;
            case STATIC -> 45;
            case JUMPSCARE -> 16;
        };
        if (age > max) {
            onClose();
        }
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(null);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        switch (mode) {
            case LOST -> g.fill(0, 0, width, height, 0xFF17130F);
            case STATIC -> noise(g, 700, 0xFF000000);
            case JUMPSCARE -> face(g);
        }
    }

    private void noise(GuiGraphics g, int count, int bg) {
        g.fill(0, 0, width, height, bg);
        for (int i = 0; i < count; i++) {
            int x = rnd.nextInt(Math.max(1, width));
            int y = rnd.nextInt(Math.max(1, height));
            int s = 1 + rnd.nextInt(3);
            int v = rnd.nextInt(256);
            g.fill(x, y, x + s * 3, y + s, 0xFF000000 | (v << 16) | (v << 8) | v);
        }
        if (rnd.nextInt(3) == 0) {
            int y = rnd.nextInt(Math.max(1, height));
            g.fill(0, y, width, y + 2 + rnd.nextInt(6), 0x55FFFFFF);
        }
    }

    private void face(GuiGraphics g) {
        g.fill(0, 0, width, height, 0xFF000000);
        int cx = width / 2 + rnd.nextInt(9) - 4;
        int cy = height / 2 + rnd.nextInt(9) - 4;
        int s = (int) (Math.min(width, height) * (0.55 + age * 0.05));
        int h = s / 2;
        // head
        g.fill(cx - h, cy - h, cx + h, cy + h, 0xFF0E0E0E);
        g.fill(cx - h + s / 16, cy - h + s / 16, cx + h - s / 16, cy + h - s / 16, 0xFF151515);
        // eyes: red glow + white core
        int ey = cy - s / 10;
        int ex = s / 5;
        int es = Math.max(2, s / 14);
        for (int side = -1; side <= 1; side += 2) {
            int x = cx + side * ex;
            g.fill(x - es * 2, ey - es * 2, x + es * 2, ey + es * 2, 0x55AA0000);
            g.fill(x - es, ey - es, x + es, ey + es, 0xFFFFFFFF);
        }
        // mouth: a long dark crack
        g.fill(cx - s / 6, cy + s / 5, cx + s / 6, cy + s / 5 + Math.max(2, s / 40), 0xFF000000);
        g.fill(cx - 1, cy + s / 5, cx + 1, cy + h - s / 12, 0xFF000000);
        // scanlines
        for (int y = 0; y < height; y += 3) {
            g.fill(0, y, width, y + 1, 0x33000000);
        }
        if (rnd.nextInt(2) == 0) {
            int y = rnd.nextInt(Math.max(1, height));
            g.fill(0, y, width, y + 3, 0x66FFFFFF);
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        switch (mode) {
            case LOST -> {
                g.drawCenteredString(font, Component.translatable("disconnect.lost"), width / 2, height / 2 - 50, 0xAAAAAA);
                g.drawCenteredString(font, reason, width / 2, height / 2 - 20, 0xFFFFFF);
            }
            case STATIC -> {
                if ((age / 3) % 2 == 0 && !text.isEmpty()) {
                    g.drawCenteredString(font, Component.literal(text), width / 2 + rnd.nextInt(5) - 2,
                            height / 2 + rnd.nextInt(5) - 2, 0xFFCC0000);
                }
            }
            case JUMPSCARE -> { }
        }
    }
}
