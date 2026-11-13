package fr.dynamx.client.renders.lights;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

/**
 * Textures générées à la volée (au premier rendu, jamais rechargées) pour le rendu de {@link DynamXLightFx}, afin de
 * ne pas embarquer d'assets.
 */
@SideOnly(Side.CLIENT)
public class LightFxTextures {
    /**
     * Taille des dégradés radiaux : 64 suffit, ce sont des flous
     */
    private static final int SIZE = 64;
    /**
     * Taille des profils de faisceau, lus sur des routes entières et non sur un sprite
     */
    private static final int PROFILE_SIZE = 256;

    /**
     * Halo dessiné à la position de la lampe : coeur très lumineux, chute rapide
     */
    private static DynamicTexture flare;
    /**
     * Tache de lumière projetée sur une surface : dégradé large et doux
     */
    private static DynamicTexture spot;
    private static DynamicTexture profile;
    private static DynamicTexture groundProfile;

    /**
     * Conversion d'une position en travers du faisceau (-1 à 1) en coordonnée de
     * {@link LightFxTextures#getProfileTextureId()} : {@code CENTER + position * SCALE}. Les deux bords tombent au
     * centre des pixels extrêmes, qui valent zéro : avec un clamp sur le bord, la lumière s'arrête donc exactement
     * où le faisceau s'arrête.
     */
    public static final float PROFILE_CENTER = 0.5f, PROFILE_SCALE = (PROFILE_SIZE - 1) / (2f * PROFILE_SIZE);
    /**
     * Idem pour la position le long du faisceau (0 à la lampe, 1 au bout) sur la seconde coordonnée de
     * {@link LightFxTextures#getGroundProfileTextureId()} : {@code OFFSET + position * SCALE}
     */
    public static final float PROFILE_ALONG_OFFSET = 0.5f / PROFILE_SIZE,
            PROFILE_ALONG_SCALE = (PROFILE_SIZE - 1f) / PROFILE_SIZE;

    public static int getFlareTextureId() {
        if (flare == null) {
            // Volontairement sans coeur opaque : un dégradé à centre plat donne une boule de lumière collée à la
            // lampe, celui-ci donne un halo autour d'elle
            flare = generate(2.6f, 0);
        }
        return flare.getGlTextureId();
    }

    public static int getSpotTextureId() {
        if (spot == null) {
            spot = generate(1.9f, 0.5f);
        }
        return spot.getGlTextureId();
    }

    /**
     * Dégradé de la lumière d'un projecteur posée sur une surface : part de lumière reçue selon la position en
     * travers du faisceau (premier axe) et vers le haut (second axe). <br>
     * Lu pixel par pixel plutôt que calculé aux coins des blocs : ajusté aux coins, le bord du faisceau descendait
     * en marches d'un bloc, changeait selon la finesse du découpage, et la lumière d'un véhicule garé bougeait donc
     * quand le joueur tournait autour.
     *
     * @see LightFxTextures#PROFILE_CENTER
     */
    public static int getProfileTextureId() {
        if (profile == null) {
            profile = generateProfile(false);
        }
        return profile.getGlTextureId();
    }

    /**
     * Génère un dégradé radial
     *
     * @param falloff  Exposant de la chute radiale : plus il est grand, plus le dégradé est net
     * @param coreSize Rayon (0-1) du centre totalement opaque
     */
    private static DynamicTexture generate(float falloff, float coreSize) {
        DynamicTexture texture = new DynamicTexture(SIZE, SIZE);
        int[] data = texture.getTextureData();
        float center = (SIZE - 1) / 2f;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                float dx = (x - center) / center;
                float dy = (y - center) / center;
                float distance = (float) Math.sqrt(dx * dx + dy * dy);
                float alpha;
                if (distance >= 1) {
                    alpha = 0;
                } else if (distance <= coreSize) {
                    alpha = 1;
                } else {
                    alpha = (float) Math.pow(1 - (distance - coreSize) / (1 - coreSize), falloff);
                }
                int level = (int) (alpha * 255);
                // Stocké dans les canaux rgb et non dans l'alpha, pour moduler la couleur de la lumière (qui vient
                // de la couleur des sommets) quel que soit le mode de blending
                data[y * SIZE + x] = 0xFF000000 | (level << 16) | (level << 8) | level;
            }
        }
        return upload(texture);
    }

    /**
     * Dégradé de la lumière d'un projecteur posée au sol : part de lumière reçue selon la position en travers du
     * faisceau (premier axe) et le long du faisceau (second axe). <br>
     * Les deux axes sont mesurés depuis le point de convergence des bords de l'empreinte, un peu derrière la lampe,
     * et non depuis la lampe : ainsi mesurés ils varient linéairement sur le sol, ce que la carte graphique
     * interpole exactement, et une route plate peut donc être dessinée en un seul quad.
     *
     * @see LightFxTextures#PROFILE_ALONG_OFFSET
     */
    public static int getGroundProfileTextureId() {
        if (groundProfile == null) {
            groundProfile = generateProfile(true);
        }
        return groundProfile.getGlTextureId();
    }

    /**
     * @param ground Vrai pour le dégradé au sol (second axe : distance le long du faisceau), faux pour celui des
     *               surfaces dans le faisceau (second axe : hauteur dans le faisceau)
     */
    private static DynamicTexture generateProfile(boolean ground) {
        DynamicTexture texture = new DynamicTexture(PROFILE_SIZE, PROFILE_SIZE);
        int[] data = texture.getTextureData();
        for (int y = 0; y < PROFILE_SIZE; y++) {
            // Distance le long du faisceau depuis le point de convergence : 0 là-bas, 1 au bout de la portée
            float along = (float) y / (PROFILE_SIZE - 1);
            float fade = ground ? DynamXLightFx.groundAlongFalloff(along) : 0;
            // Ou, pour les surfaces dans le faisceau, la hauteur, -1 et 1 sur les bords
            float up = 2f * y / (PROFILE_SIZE - 1) - 1;
            float upFade = Math.max(0, 1 - up * up);
            for (int x = 0; x < PROFILE_SIZE; x++) {
                // Position en travers, en fraction de la largeur du bout du faisceau
                float across = 2f * x / (PROFILE_SIZE - 1) - 1;
                float level;
                if (ground) {
                    // Divisée par la distance le long du faisceau, avec laquelle la largeur du faisceau croît
                    float edge = along <= 0 ? 1 : across / along;
                    level = Math.max(0, 1 - edge * edge) * fade;
                } else {
                    level = Math.max(0, 1 - across * across) * upFade;
                }
                int at = (int) (level * 255);
                data[y * PROFILE_SIZE + x] = 0xFF000000 | (at << 16) | (at << 8) | at;
            }
        }
        return upload(texture);
    }

    /**
     * Envoie la texture et la configure en dégradé : interpolée, non pixelisée et sans répétition. À faire après
     * l'envoi, qui remet le filtrage sur "nearest".
     */
    private static DynamicTexture upload(DynamicTexture texture) {
        texture.updateDynamicTexture();
        GlStateManager.bindTexture(texture.getGlTextureId());
        GlStateManager.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GlStateManager.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GlStateManager.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GlStateManager.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        return texture;
    }
}
