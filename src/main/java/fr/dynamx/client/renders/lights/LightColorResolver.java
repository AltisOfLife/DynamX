package fr.dynamx.client.renders.lights;

import fr.dynamx.client.renders.model.renderer.DxModelRenderer;
import fr.dynamx.client.renders.model.renderer.ObjModelRenderer;
import fr.dynamx.client.renders.model.renderer.ObjObjectRenderer;
import fr.dynamx.client.renders.model.texture.MaterialTexture;
import fr.dynamx.common.DynamXMain;
import fr.dynamx.common.contentpack.parts.LightFxLamp;
import fr.dynamx.common.objloader.data.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.MathHelper;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.lang.ref.SoftReference;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Détermine les couleurs des effets d'une source de lumière. La couleur configurée dans le pack ("LightColor") gagne
 * toujours ; sinon elle est devinée depuis les pixels les plus lumineux de la texture allumée, pour que l'effet
 * fonctionne sur les packs existants. <br>
 * Un objet de lumière porte rarement une seule couleur (rampe rouge et bleue, feu arrière rouge/orange/blanc) : la
 * couleur est donc lue lampe par lampe, puis tronçon par tronçon, pour que chaque module brille dans la sienne même
 * quand la rampe est modélisée d'un seul bloc. Les résultats sont mis en cache : une texture n'est lue qu'une fois
 * par objet de lumière.
 *
 * @see LightFxLamp#getUvStretches()
 */
@SideOnly(Side.CLIENT)
public class LightColorResolver {
    /**
     * Repli quand rien n'a pu être déterminé (un blanc légèrement chaud)
     */
    private static final int DEFAULT_COLOR = 0xFFF2DC;

    /**
     * Couleurs des lampes d'un objet de lumière : celle de chaque lampe entière, et celles le long de chacune
     *
     * @see LightColorResolver#resolve
     */
    public static class LightColors {
        /**
         * Couleur de chaque lampe, en rgb empaqueté, dans l'ordre où les lampes ont été données
         */
        public final int[] lamps;
        /**
         * Couleur de chaque tronçon, {@link LightFxLamp#COLOR_STRETCHES} par lampe, null pour une lampe d'une seule
         * couleur ou dont le modèle n'a pas de coordonnées de texture
         */
        public final int[][] stretches;

        LightColors(int[] lamps, int[][] stretches) {
            this.lamps = lamps;
            this.stretches = stretches;
        }
    }

    /**
     * Cache (modèle + objet + variante) -> couleurs des lampes
     */
    private static final Map<String, LightColors> COLOR_CACHE = new HashMap<>();
    /**
     * Cache texture -> couleur rgb, partagé par toutes les lumières d'une même texture
     */
    private static final Map<ResourceLocation, Integer> TEXTURE_CACHE = new HashMap<>();
    private static final int MEMO_SLOTS = 16;
    /**
     * Dernières lumières résolues et leur réponse. Chaque lampe de chaque véhicule visible interroge le même
     * modèle, le même nom d'objet et la même variante à chaque image : les comparer par identité évite de
     * construire la clé du cache ci-dessus, qui est une String et donc un déchet constant pour une réponse qui ne
     * change jamais. Plusieurs cases, car les lumières d'une zone sont demandées véhicule après véhicule.
     */
    private static final DxModelRenderer[] MEMO_MODELS = new DxModelRenderer[MEMO_SLOTS];
    private static final String[] MEMO_OBJECTS = new String[MEMO_SLOTS], MEMO_VARIANTS = new String[MEMO_SLOTS];
    private static final LightColors[] MEMO_COLORS = new LightColors[MEMO_SLOTS];
    private static int nextMemoSlot;

    /**
     * Pixels lus au plus par texture : parcourir un skin entier serait bien trop lent
     */
    private static final int MAX_SAMPLES_PER_AXIS = 128;
    /**
     * Idem pour la zone d'une lampe ou d'un tronçon, demandée neuf fois par lampe : borne surtout le cas d'un objet
     * dont les coordonnées couvrent tout le skin
     */
    private static final int MAX_REGION_SAMPLES_PER_AXIS = 32;
    /**
     * Nombre de teintes dans lesquelles les pixels lumineux sont triés
     */
    private static final int HUE_BUCKETS = 24;
    /**
     * En dessous de cette saturation, un pixel est considéré gris et non coloré
     */
    private static final float MIN_SATURATION = 0.15f;
    /**
     * Poids des pixels colorés face aux gris : une lampe couvre une toute petite part du skin, quelques pixels
     * saturés suffisent donc à conclure que la lumière est colorée
     */
    private static final float GREY_BIAS = 0.08f;
    /**
     * Écart minimal, sur un canal, pour voir deux couleurs plutôt que deux lectures de la même : deux tronçons
     * d'une même optique sortent à quelques unités d'écart
     */
    private static final int SAME_COLOR_TOLERANCE = 24;

    /**
     * Dernière texture lue, gardée car toutes les lumières d'un véhicule partagent son skin et sont résolues l'une
     * après l'autre. Référence souple : un skin est une grosse image.
     */
    private static ResourceLocation lastImagePath;
    private static SoftReference<BufferedImage> lastImage;

    /**
     * @param model       Le modèle qui rend la lumière
     * @param objectName  Le nom de l'objet 3D de la lumière
     * @param variantName Le nom du matériau/texture allumé, peut être null
     * @param lamps       Les lampes de l'objet, pour lesquelles les couleurs sont lues
     * @return Les couleurs des lampes, une par lampe et une par tronçon de chacune
     */
    public static LightColors resolve(DxModelRenderer model, String objectName, @Nullable String variantName,
                                      List<LightFxLamp> lamps) {
        for (int slot = 0; slot < MEMO_SLOTS; slot++) {
            if (MEMO_MODELS[slot] == model && MEMO_OBJECTS[slot] == objectName
                    && MEMO_VARIANTS[slot] == variantName && MEMO_COLORS[slot].lamps.length >= lamps.size()) {
                return MEMO_COLORS[slot];
            }
        }
        String key = model.getLocation() + "@" + objectName + "@" + variantName;
        LightColors cached = COLOR_CACHE.get(key);
        if (cached != null && cached.lamps.length >= lamps.size()) {
            remember(model, objectName, variantName, cached);
            return cached;
        }
        LightColors colors = null;
        try {
            colors = findColors(model, objectName, variantName, lamps);
        } catch (Exception e) {
            // Ne jamais casser le rendu à cause des effets de lumière
            DynamXMain.log.warn("Cannot determine the light color of " + objectName + " in " + model.getLocation(), e);
        }
        if (colors == null) {
            int[] fallback = new int[lamps.size()];
            Arrays.fill(fallback, DEFAULT_COLOR);
            colors = new LightColors(fallback, new int[lamps.size()][]);
        }
        COLOR_CACHE.put(key, colors);
        remember(model, objectName, variantName, colors);
        return colors;
    }

    /**
     * Lit la couleur de chaque lampe et de chaque tronçon sur la texture de l'état allumé. Seul ce que la texture
     * contient réellement est renvoyé : une lampe d'une seule couleur n'a aucun tronçon, et tout l'objet retombe
     * sur une couleur unique si le modèle n'a pas de coordonnées de texture.
     *
     * @return Les couleurs des lampes, ou null si la lumière n'a aucune texture à lire
     */
    @Nullable
    private static LightColors findColors(DxModelRenderer model, String objectName, @Nullable String variantName,
                                          List<LightFxLamp> lamps) {
        ResourceLocation path = findTexture(model, objectName, variantName);
        Integer wholeColor = path == null ? null : resolveTexture(path);
        if (wholeColor == null) {
            // Pas de texture, ou illisible : le matériau allumé est peint en couleur unie
            wholeColor = findMaterialColor(model, objectName, variantName);
        }
        BufferedImage image = path == null ? null : readImage(path);
        int[] lampColors = new int[lamps.size()];
        int[][] stretchColors = new int[lamps.size()][];
        int fallback = wholeColor == null ? DEFAULT_COLOR : wholeColor;
        for (int index = 0; index < lamps.size(); index++) {
            LightFxLamp lamp = lamps.get(index);
            float[] uvStretches = lamp.getUvStretches();
            lampColors[index] = fallback;
            if (image == null || uvStretches == null) {
                continue;
            }
            // Couleur de la lampe entière lue sur tous ses pixels, et non déduite des tronçons : la couleur
            // dominante d'une lampe n'est celle d'aucun de ses tronçons, et c'est celle de ses faisceaux et de la
            // lumière qu'elle pose au sol
            Integer color = regionColor(image, lampBounds(uvStretches), 0);
            if (color != null) {
                lampColors[index] = color;
            }
            int[] along = new int[LightFxLamp.COLOR_STRETCHES];
            boolean several = false;
            for (int stretch = 0; stretch < along.length; stretch++) {
                Integer stretchColor = regionColor(image, uvStretches, stretch * 4);
                along[stretch] = stretchColor == null ? lampColors[index] : stretchColor;
                several |= !sameColor(along[stretch], along[0]);
            }
            // Une lampe d'une seule couleur reste sans tronçons : son halo est alors dessiné d'un seul coup
            if (several) {
                stretchColors[index] = along;
            }
        }
        return new LightColors(lampColors, stretchColors);
    }

    /**
     * @return La zone de texture de la lampe entière (u min, v min, u max, v max de ses tronçons)
     */
    private static float[] lampBounds(float[] uvStretches) {
        float[] bounds = {Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (int stretch = 0; stretch * 4 < uvStretches.length; stretch++) {
            int at = stretch * 4;
            if (uvStretches[at] > uvStretches[at + 2]) {
                // Aucun sommet de la lampe ne tombe dans ce tronçon
                continue;
            }
            bounds[0] = Math.min(bounds[0], uvStretches[at]);
            bounds[1] = Math.min(bounds[1], uvStretches[at + 1]);
            bounds[2] = Math.max(bounds[2], uvStretches[at + 2]);
            bounds[3] = Math.max(bounds[3], uvStretches[at + 3]);
        }
        return bounds;
    }

    /**
     * @return Vrai si les deux couleurs sont la même lue deux fois, plutôt que deux couleurs
     * @see LightColorResolver#SAME_COLOR_TOLERANCE
     */
    public static boolean sameColor(int first, int second) {
        return Math.abs(((first >> 16) & 0xFF) - ((second >> 16) & 0xFF)) <= SAME_COLOR_TOLERANCE
                && Math.abs(((first >> 8) & 0xFF) - ((second >> 8) & 0xFF)) <= SAME_COLOR_TOLERANCE
                && Math.abs((first & 0xFF) - (second & 0xFF)) <= SAME_COLOR_TOLERANCE;
    }

    /**
     * Note la lumière qui vient d'être résolue, dans la plus ancienne case du mémo
     */
    private static void remember(DxModelRenderer model, String objectName, @Nullable String variantName,
                                 LightColors color) {
        int slot = nextMemoSlot;
        nextMemoSlot = slot + 1 == MEMO_SLOTS ? 0 : slot + 1;
        MEMO_MODELS[slot] = model;
        MEMO_OBJECTS[slot] = objectName;
        MEMO_VARIANTS[slot] = variantName;
        MEMO_COLORS[slot] = color;
    }

    /**
     * Vide les caches, appelé au rechargement des modèles
     */
    public static void clearCache() {
        COLOR_CACHE.clear();
        TEXTURE_CACHE.clear();
        lastImagePath = null;
        lastImage = null;
        for (int slot = 0; slot < MEMO_SLOTS; slot++) {
            MEMO_MODELS[slot] = null;
            MEMO_OBJECTS[slot] = null;
            MEMO_VARIANTS[slot] = null;
        }
    }

    /**
     * Cherche le matériau que le renderer utilise pour l'état allumé, et donc celui dont la texture est lue. <br>
     * Résolu comme {@link ObjObjectRenderer} le fait, depuis les matériaux réellement utilisés par l'objet 3D, et
     * non en cherchant un matériau portant le nom de la variante : un modèle peut contenir un matériau nommé comme
     * la variante d'une autre lumière ("on"), ce qui peindrait toutes les lumières dans la couleur de l'une
     * d'elles. Si aucun matériau de l'objet ne porte l'état allumé, l'objet est simplement dessiné avec sa propre
     * texture éclaircie : c'est celle-là qu'il faut lire.
     *
     * @return Le matériau de l'état allumé, ou null si le modèle ne l'expose pas
     */
    @Nullable
    private static Material findMaterial(DxModelRenderer model, String objectName, @Nullable String variantName) {
        // Seuls les modèles obj exposent leurs matériaux : les gltf retombent sur la couleur par défaut
        if (!(model instanceof ObjModelRenderer)) {
            return null;
        }
        ObjModelRenderer objModel = (ObjModelRenderer) model;
        String variant = variantName == null ? null : variantName.toLowerCase();
        Material lit = null;
        ObjObjectRenderer object = objModel.getObjObjectRenderer(objectName);
        if (object != null) {
            for (String materialName : object.getObjObjectData().getMaterials().keySet()) {
                Material candidate = objModel.getMaterials().get(materialName);
                if (candidate == null) {
                    continue;
                }
                // L'état allumé en texture, nommée comme la variante, d'un matériau de l'objet 3D
                if (variant != null && candidate.diffuseTexture.containsKey(variant)) {
                    return candidate;
                }
                if (lit == null) {
                    lit = candidate;
                }
            }
        }
        // Ou en matériau à part, nommé comme la variante, que le renderer substitue. Regardé en second : les
        // matériaux de l'objet sont ce avec quoi il est réellement dessiné.
        if (variant != null) {
            Material named = objModel.getMaterials().get(variant);
            if (named != null) {
                return named;
            }
        }
        // Ni l'un ni l'autre : la lumière est dessinée avec la seule texture de son objet
        return lit;
    }

    /**
     * @return La texture de l'état allumé, ou null s'il n'y en a aucune à lire
     */
    @Nullable
    private static ResourceLocation findTexture(DxModelRenderer model, String objectName,
                                                @Nullable String variantName) {
        Material material = findMaterial(model, objectName, variantName);
        if (material == null) {
            return null;
        }
        MaterialTexture texture = variantName == null ? material.diffuseTexture.get("default")
                : material.diffuseTexture.getOrDefault(variantName.toLowerCase(),
                material.diffuseTexture.get("default"));
        return texture == null ? null : texture.getPath();
    }

    /**
     * @return La couleur unie du matériau de l'état allumé quand il n'a aucune texture, ou null si le modèle ne
     * l'expose pas
     */
    @Nullable
    private static Integer findMaterialColor(DxModelRenderer model, String objectName,
                                             @Nullable String variantName) {
        Material material = findMaterial(model, objectName, variantName);
        return material == null ? null
                : fullIntensity(material.diffuseColor.x, material.diffuseColor.y, material.diffuseColor.z);
    }

    /**
     * Couleur moyenne des pixels les plus lumineux de la texture, qui sont ceux de la partie allumée de la lampe.
     * C'est la couleur de tout l'objet, sur laquelle retombent les lampes indiscernables sur la texture.
     */
    @Nullable
    private static Integer resolveTexture(ResourceLocation path) {
        if (TEXTURE_CACHE.containsKey(path)) {
            return TEXTURE_CACHE.get(path);
        }
        Integer color = null;
        BufferedImage image = readImage(path);
        if (image != null) {
            color = computeColor(image, 0, 0, image.getWidth() - 1, image.getHeight() - 1, MAX_SAMPLES_PER_AXIS);
        }
        TEXTURE_CACHE.put(path, color);
        return color;
    }

    /**
     * Lit la texture, ou la reprend dans la case de la dernière lue
     *
     * @return L'image, ou null si elle n'a pas pu être lue
     */
    @Nullable
    private static BufferedImage readImage(ResourceLocation path) {
        if (path.equals(lastImagePath) && lastImage != null) {
            BufferedImage kept = lastImage.get();
            if (kept != null) {
                return kept;
            }
        }
        BufferedImage image = null;
        try {
            image = TextureUtil.readBufferedImage(Minecraft.getMinecraft().getResourceManager()
                    .getResource(path).getInputStream());
        } catch (Exception e) {
            DynamXMain.log.warn("Cannot read the light texture " + path + " to determine the light color");
        }
        lastImagePath = path;
        lastImage = image == null ? null : new SoftReference<>(image);
        return image;
    }

    /**
     * Couleur de la zone de texture d'une lampe ou d'un de ses tronçons
     *
     * @param uv La zone, en u min, v min, u max, v max, à partir du décalage donné
     * @param at Le décalage de ces quatre valeurs dans le tableau
     * @return La couleur de cette zone, ou null si rien n'y est peint
     */
    @Nullable
    private static Integer regionColor(BufferedImage image, float[] uv, int at) {
        float uMin = uv[at], vMin = uv[at + 1], uMax = uv[at + 2], vMax = uv[at + 3];
        if (uMin > uMax || vMin > vMax) {
            // Rien de la lampe ne tombe dans ce tronçon
            return null;
        }
        int width = image.getWidth(), height = image.getHeight();
        // Les coordonnées d'un modèle peuvent dépasser la texture, que le renderer répète : telles quelles, on lit
        // le bord de la texture, ce qui suffit pour une couleur de lampe
        int x0 = MathHelper.clamp((int) Math.floor(uMin * width), 0, width - 1);
        int x1 = MathHelper.clamp((int) Math.ceil(uMax * width) - 1, x0, width - 1);
        int y0 = MathHelper.clamp((int) Math.floor(vMin * height), 0, height - 1);
        int y1 = MathHelper.clamp((int) Math.ceil(vMax * height) - 1, y0, height - 1);
        return computeColor(image, x0, y0, x1, y1, MAX_REGION_SAMPLES_PER_AXIS);
    }

    /**
     * Couleur de la lampe peinte sur cette zone de texture, coins inclus. <br>
     * Moyenner les pixels lumineux ne suffit pas : une texture de gyrophare porte une lampe rouge et une bleue,
     * dont la moyenne est un violet qui ne correspond à aucune. Les pixels lumineux sont donc triés en histogramme
     * de teintes, pondérés par leur luminosité et leur saturation, et seuls ceux de la teinte dominante sont
     * moyennés. Une texture dont les pixels lumineux sont tous grisâtres donne un blanc.
     */
    @Nullable
    private static Integer computeColor(BufferedImage image, int x0, int y0, int x1, int y1, int maxSamplesPerAxis) {
        int width = x1 - x0 + 1;
        int height = y1 - y0 + 1;
        if (width <= 0 || height <= 0) {
            return null;
        }
        int stepX = Math.max(1, width / maxSamplesPerAxis);
        int stepY = Math.max(1, height / maxSamplesPerAxis);

        // Première passe : le pixel le plus lumineux de la zone
        float maxBrightness = 0;
        for (int y = y0; y <= y1; y += stepY) {
            for (int x = x0; x <= x1; x += stepX) {
                int argb = image.getRGB(x, y);
                if ((argb >>> 24) < 16) {
                    continue;
                }
                maxBrightness = Math.max(maxBrightness, brightness(argb));
            }
        }
        if (maxBrightness <= 0) {
            return null;
        }

        // Seconde passe : tri des pixels lumineux par teinte
        float threshold = maxBrightness * 0.6f;
        double[] hueWeights = new double[HUE_BUCKETS];
        double[] hueR = new double[HUE_BUCKETS], hueG = new double[HUE_BUCKETS], hueB = new double[HUE_BUCKETS];
        double greyWeight = 0, greyR = 0, greyG = 0, greyB = 0;
        float[] hsb = new float[3];
        for (int y = y0; y <= y1; y += stepY) {
            for (int x = x0; x <= x1; x += stepX) {
                int argb = image.getRGB(x, y);
                if ((argb >>> 24) < 16 || brightness(argb) < threshold) {
                    continue;
                }
                int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
                Color.RGBtoHSB(r, g, b, hsb);
                // Une lampe allumée est à la fois lumineuse et saturée, ce qui la détache du reste du skin
                double weight = brightness(argb) / 255d;
                if (hsb[1] < MIN_SATURATION) {
                    greyWeight += weight;
                    greyR += r * weight;
                    greyG += g * weight;
                    greyB += b * weight;
                    continue;
                }
                weight *= hsb[1];
                int bucket = Math.min(HUE_BUCKETS - 1, (int) (hsb[0] * HUE_BUCKETS));
                hueWeights[bucket] += weight;
                hueR[bucket] += r * weight;
                hueG[bucket] += g * weight;
                hueB[bucket] += b * weight;
            }
        }

        // Teinte dominante, chaque casier étant fusionné avec ses deux voisins pour qu'une teinte à cheval sur deux
        // casiers ne soit pas coupée en deux
        int bestBucket = -1;
        double bestWeight = 0;
        for (int bucket = 0; bucket < HUE_BUCKETS; bucket++) {
            double weight = hueWeights[previousBucket(bucket)] + hueWeights[bucket] + hueWeights[nextBucket(bucket)];
            if (weight > bestWeight) {
                bestWeight = weight;
                bestBucket = bucket;
            }
        }
        // Une lampe colorée gagne même en très petit nombre face aux pixels gris, qui couvrent une zone bien plus
        // grande que la lampe elle-même
        if (bestBucket >= 0 && bestWeight > greyWeight * GREY_BIAS) {
            int previous = previousBucket(bestBucket), next = nextBucket(bestBucket);
            double weight = hueWeights[previous] + hueWeights[bestBucket] + hueWeights[next];
            return fullIntensity((float) ((hueR[previous] + hueR[bestBucket] + hueR[next]) / weight / 255),
                    (float) ((hueG[previous] + hueG[bestBucket] + hueG[next]) / weight / 255),
                    (float) ((hueB[previous] + hueB[bestBucket] + hueB[next]) / weight / 255));
        }
        if (greyWeight > 0) {
            return fullIntensity((float) (greyR / greyWeight / 255), (float) (greyG / greyWeight / 255),
                    (float) (greyB / greyWeight / 255));
        }
        return null;
    }

    private static int previousBucket(int bucket) {
        return (bucket + HUE_BUCKETS - 1) % HUE_BUCKETS;
    }

    private static int nextBucket(int bucket) {
        return (bucket + 1) % HUE_BUCKETS;
    }

    /**
     * Luminosité d'un pixel de lampe : son canal le plus fort, et non sa luminance perçue. Pondéré
     * perceptuellement, le bleu et le rouge d'une texture portant aussi du blanc passeraient tous sous le seuil et
     * seul le blanc serait lu.
     */
    private static float brightness(int argb) {
        return Math.max((argb >> 16) & 0xFF, Math.max((argb >> 8) & 0xFF, argb & 0xFF));
    }

    /**
     * Pousse la couleur à pleine intensité, teinte et saturation inchangées, pour que les effets montrent la
     * couleur de la lampe et non une version délavée. Leur luminosité est gérée à part, par leur intensité.
     */
    private static int fullIntensity(float r, float g, float b) {
        float max = Math.max(r, Math.max(g, b));
        if (max <= 0.001f) {
            return DEFAULT_COLOR;
        }
        r /= max;
        g /= max;
        b /= max;
        return ((int) (r * 255) << 16) | ((int) (g * 255) << 8) | (int) (b * 255);
    }
}
