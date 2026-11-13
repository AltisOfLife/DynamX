package fr.dynamx.client.gui;

import fr.dynamx.client.renders.lights.DynamXLightFx;
import fr.dynamx.utils.DynamXConfig;
import fr.dynamx.utils.LightFxPreset;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;

/**
 * Page de réglages des effets de lumière, ouverte depuis les options vidéo et présentée comme elles : un bouton par
 * réglage, clic gauche pour la valeur suivante, clic droit pour la précédente. <br>
 * Rien n'est conservé ici : chaque changement est écrit tout de suite dans la config et relu à chaque affichage,
 * pour que la page suive aussi ce que la commande ou le fichier de config ont fait.
 *
 * @see DynamXLightFx
 * @see fr.dynamx.client.command.CmdLightFx
 */
@SideOnly(Side.CLIENT)
public class GuiLightFxSettings extends GuiScreen {
    /**
     * Identifiant du bouton de sortie, celui qu'utilisent les options du jeu
     */
    private static final int DONE_BUTTON = 200;
    /**
     * Celui du bouton de préréglage, numéroté au-delà des réglages (numérotés par leur place dans la page)
     */
    private static final int PRESET_BUTTON = 100;

    private static final int[] INTENSITY_VALUES = {25, 50, 75, 100, 125, 150, 200, 250, 300};
    private static final int[] DISTANCE_VALUES = {16, 24, 32, 48, 64, 96, 128, 192, 256};
    private static final int[] MAX_LIGHTS_VALUES = {8, 16, 24, 32, 48, 64, 96, 128, 192, 256};

    /**
     * Écran où retourne le bouton de sortie
     */
    private final GuiScreen parent;
    /**
     * Réglages de la page, dans leur ordre d'affichage, chacun étant le bouton de son propre index
     */
    private final List<Setting> settings = new ArrayList<>();

    public GuiLightFxSettings(GuiScreen parent) {
        this.parent = parent;
        settings.add(new Setting("Effets", switchValues(), "Les halos des lampes allumées, la lumière qu'elles"
                + " projettent sur le sol et les murs, et les faisceaux des gyrophares.",
                () -> DynamXConfig.enableLightFx ? 1 : 0, value -> {
            DynamXConfig.setEnableLightFx(value != 0);
            if (value == 0) {
                // Jette les lumières déjà collectées pour l'image en cours
                DynamXLightFx.clear();
            }
        }, value -> value != 0 ? "Activés" : "Désactivés"));
        settings.add(new Setting("Gyrophares seuls", switchValues(), "Ne donne les effets qu'aux gyrophares, et"
                + " pas aux phares. Le réglage le plus léger qui montre encore les lumières des véhicules"
                + " d'urgence.",
                () -> DynamXConfig.lightFxBeaconsOnly ? 1 : 0,
                value -> DynamXConfig.setLightFxBeaconsOnly(value != 0),
                value -> value != 0 ? "Oui" : "Non"));
        settings.add(new Setting("Mon véhicule seul", switchValues(), "Ne donne les effets qu'au véhicule dans"
                + " lequel vous êtes, et à aucun autre véhicule ni bloc. À pied, plus aucun effet n'est dessiné."
                + " Le réglage le plus léger de tous : quoi qu'il se passe autour de vous, les effets ne coûtent"
                + " jamais plus qu'un seul véhicule.",
                () -> DynamXConfig.lightFxOwnVehicleOnly ? 1 : 0,
                value -> DynamXConfig.setLightFxOwnVehicleOnly(value != 0),
                value -> value != 0 ? "Oui" : "Non"));
        settings.add(new Setting("Animation gyro rotatif", switchValues(), "Laisse les gyrophares qui tournent sur"
                + " eux-mêmes balayer la lumière autour d'eux : les faisceaux qu'ils projettent et les deux lobes"
                + " brillants que leurs miroirs font en passant devant la caméra. Désactivée, ces gyrophares"
                + " gardent le halo de leur lampe et rien de plus.",
                () -> DynamXConfig.lightFxRotatingBeaconSweep ? 1 : 0,
                value -> DynamXConfig.setLightFxRotatingBeaconSweep(value != 0),
                value -> value != 0 ? "Activée" : "Désactivée"));
        settings.add(new Setting("Intensité", INTENSITY_VALUES, "Luminosité des effets.",
                () -> Math.round(DynamXConfig.lightFxIntensity * 100),
                percent -> DynamXConfig.setLightFxIntensity(percent / 100f),
                percent -> percent + " %"));
        settings.add(new Setting("Distance", DISTANCE_VALUES, "Distance au-delà de laquelle les effets ne sont plus"
                + " dessinés. Règle aussi jusqu'où ils gardent leur finesse : plus loin, la lumière d'un phare"
                + " reste détaillée longtemps au lieu de se simplifier à quelques blocs.",
                () -> DynamXConfig.lightFxMaxDistance, DynamXConfig::setLightFxMaxDistance,
                blocks -> blocks + " blocs"));
        settings.add(new Setting("Lumières max.", MAX_LIGHTS_VALUES, "Nombre de lumières dessinées en même temps."
                + " Les plus proches de la caméra sont gardées, et les dernières qui tiennent dans ce nombre sont"
                + " atténuées au lieu de disparaître d'un coup.",
                () -> DynamXConfig.lightFxMaxLights, DynamXConfig::setLightFxMaxLights,
                lights -> Integer.toString(lights)));
    }

    @Override
    public void initGui() {
        buttonList.clear();
        for (int i = 0; i < settings.size(); i++) {
            // Les deux colonnes des options du jeu
            buttonList.add(new GuiButton(i, width / 2 - 155 + i % 2 * 160, height / 6 - 12 + 24 * (i >> 1),
                    150, 20, ""));
        }
        int at = settings.size();
        buttonList.add(new GuiButton(PRESET_BUTTON, width / 2 - 155 + at % 2 * 160,
                height / 6 - 12 + 24 * (at >> 1), 150, 20, ""));
        buttonList.add(new GuiButton(DONE_BUTTON, width / 2 - 100, height / 6 + 168, I18n.format("gui.done")));
        refresh();
    }

    /**
     * Réécrit la valeur de chaque bouton et grise ceux qui ne servent à rien effets éteints. Appelé après tout
     * changement, et pas seulement sur le bouton cliqué : un préréglage change tous les réglages d'un coup.
     */
    private void refresh() {
        for (int i = 0; i < settings.size(); i++) {
            Setting setting = settings.get(i);
            GuiButton button = buttonList.get(i);
            button.displayString = setting.name + " : " + setting.nameOfValue(setting.read.getAsInt());
            // Le premier réglage est celui qui allume les effets : les suivants ne font rien sans lui
            button.enabled = i == 0 || DynamXConfig.enableLightFx;
        }
        LightFxPreset applied = appliedPreset();
        buttonList.get(settings.size()).displayString = "Préréglage : "
                + (applied == null ? "Perso." : applied.getDisplayName());
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button.id == DONE_BUTTON) {
            mc.displayGuiScreen(parent);
            return;
        }
        step(button, 1);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (mouseButton == 1) {
            // Le jeu ne signale que le clic gauche à actionPerformed : le clic droit, qui recule dans les valeurs,
            // est donc récupéré ici
            for (GuiButton button : buttonList) {
                if (button.id != DONE_BUTTON && button.mousePressed(mc, mouseX, mouseY)) {
                    button.playPressSound(mc.getSoundHandler());
                    step(button, -1);
                    return;
                }
            }
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    /**
     * Passe un réglage à la valeur suivante (step 1) ou précédente (step -1)
     */
    private void step(GuiButton button, int step) {
        if (button.id == PRESET_BUTTON) {
            LightFxPreset applied = appliedPreset();
            // Hors préréglage, les deux sens entrent dans la liste : les réglages sont ceux du joueur, et le
            // préréglage le plus proche ne se devine pas
            int at = applied == null ? (step > 0 ? 0 : LightFxPreset.values().length - 1)
                    : Math.floorMod(applied.ordinal() + step, LightFxPreset.values().length);
            LightFxPreset.values()[at].apply();
        } else {
            Setting setting = settings.get(button.id);
            int at = Math.floorMod(setting.indexOf(setting.read.getAsInt()) + step, setting.values.length);
            setting.write.accept(setting.values[at]);
        }
        refresh();
    }

    /**
     * @return Le préréglage exactement appliqué, ou null si les réglages sont ceux du joueur
     */
    private static LightFxPreset appliedPreset() {
        for (LightFxPreset preset : LightFxPreset.values()) {
            if (preset.isApplied()) {
                return preset;
            }
        }
        return null;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawCenteredString(fontRenderer, "Effets de lumière DynamX", width / 2, 15, 16777215);
        super.drawScreen(mouseX, mouseY, partialTicks);
        // Aide du réglage survolé, dessinée par-dessus le reste
        for (int i = 0; i < settings.size(); i++) {
            GuiButton button = buttonList.get(i);
            if (button.isMouseOver()) {
                drawHoveringText(wrap(settings.get(i).help), mouseX, mouseY);
                return;
            }
        }
        if (buttonList.get(settings.size()).isMouseOver()) {
            drawHoveringText(wrap("Règle d'un coup la distance, le nombre de lumières et les lumières qui en"
                    + " reçoivent. Minimal ne garde que les gyrophares, et c'est le réglage à choisir pour une"
                    + " zone remplie de véhicules allumés."), mouseX, mouseY);
        }
    }

    /**
     * Découpe une aide en lignes : elle est dessinée là où se trouve la souris, et une ligne trop longue sortirait
     * de l'écran
     */
    private static List<String> wrap(String help) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : help.split(" ")) {
            if (line.length() > 0 && line.length() + word.length() > 44) {
                lines.add(line.toString());
                line.setLength(0);
            }
            line.append(line.length() == 0 ? "" : " ").append(word);
        }
        if (line.length() > 0) {
            lines.add(line.toString());
        }
        return lines;
    }

    /**
     * @return Les valeurs d'un réglage à deux états
     */
    private static int[] switchValues() {
        return new int[]{0, 1};
    }

    /**
     * Un réglage de la page : ses valeurs possibles, leur nom, et où il est rangé. La valeur est lue et écrite dans
     * la config, jamais conservée ici.
     */
    private static class Setting {
        private final String name;
        /**
         * Valeurs possibles, de la plus basse à la plus haute
         */
        private final int[] values;
        /**
         * Aide affichée au survol du bouton
         */
        private final String help;
        private final IntSupplier read;
        private final IntConsumer write;
        private final IntFunction<String> display;

        private Setting(String name, int[] values, String help, IntSupplier read, IntConsumer write,
                        IntFunction<String> display) {
            this.name = name;
            this.values = values;
            this.help = help;
            this.read = read;
            this.write = write;
            this.display = display;
        }

        private String nameOfValue(int value) {
            return display.apply(values[indexOf(value)]);
        }

        /**
         * @return L'index de la valeur la plus proche de celle lue : la config est aussi écrite à la main, et une
         * valeur qui n'est pas dans la liste ne doit pas être perdue
         */
        private int indexOf(int value) {
            int closest = 0;
            for (int i = 1; i < values.length; i++) {
                if (Math.abs(values[i] - value) < Math.abs(values[closest] - value)) {
                    closest = i;
                }
            }
            return closest;
        }
    }
}
