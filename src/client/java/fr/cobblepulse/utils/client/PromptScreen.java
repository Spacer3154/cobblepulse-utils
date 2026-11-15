package fr.cobblepulse.utils.client;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Fenetre de saisie (prix, nom, duree, commande...) affichee par-dessus un menu CobblePulse. Valider envoie la reponse
 * au serveur ; Annuler ou Echap revient au menu.
 */
public final class PromptScreen extends Screen implements Overlay {
    Screen parent;
    private final Component prompt;
    private EditBox box;
    private int bx, by, bw, bh;

    PromptScreen(Screen parent, Component prompt) {
        super(Component.literal("Saisie"));
        this.parent = parent;
        this.prompt = prompt;
    }

    @Override
    protected void init() {
        if (parent != null) parent.init(minecraft, width, height);
        bw = Math.min(340, width - 40);
        List<FormattedCharSequence> lines = font.split(prompt, bw - 24);
        bh = 22 + Math.min(4, lines.size()) * 10 + 60;
        bx = (width - bw) / 2;
        by = (height - bh) / 2;
        String old = box == null ? "" : box.getValue();
        int fy = by + 16 + Math.min(4, lines.size()) * 10 + 6;
        box = new EditBox(font, bx + 12, fy, bw - 24, 18, Component.literal("Réponse"));
        box.setMaxLength(256);
        box.setValue(old);
        addRenderableWidget(box);
        setInitialFocus(box);
        int half = (bw - 24 - 6) / 2;
        addRenderableWidget(Button.builder(Component.literal("Valider"), b -> submit()).bounds(bx + 12, fy + 26, half, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Annuler"), b -> onClose()).bounds(bx + 18 + half, fy + 26, half, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        if (parent != null) parent.render(g, -1, -1, pt);
        else renderTransparentBackground(g);
        g.pose().pushPose();
        g.pose().translate(0, 0, 400);
        g.fill(0, 0, width, height, 0x88000000);
        Ui.panel(g, bx, by, bw, bh, Ui.PANEL, Ui.ACCENT);
        List<FormattedCharSequence> lines = font.split(prompt, bw - 24);
        for (int i = 0; i < Math.min(4, lines.size()); i++) g.drawString(font, lines.get(i), bx + 12, by + 12 + i * 10, Ui.TEXT, false);
        super.render(g, mx, my, pt);
        g.pose().popPose();
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            submit();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    void submit() {
        submit(box == null ? "" : box.getValue());
    }

    void submit(String text) {
        JsonObject o = new JsonObject();
        o.addProperty("text", text);
        Ui.send("prompt_answer", o);
        if (minecraft != null) minecraft.setScreen(null);
    }

    void setText(String s) {
        if (box != null) box.setValue(s);
    }

    Component prompt() {
        return prompt;
    }

    /** Annuler : retour au menu (toujours valide cote serveur). */
    @Override
    public void onClose() {
        JsonObject o = new JsonObject();
        o.addProperty("cancel", true);
        Ui.send("prompt_answer", o);
        if (minecraft != null) minecraft.setScreen(parent instanceof MenuScreen ? parent : null);
    }

    @Override
    public Screen parentScreen() {
        return parent;
    }

    @Override
    public void setParentScreen(Screen s) {
        parent = s;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
