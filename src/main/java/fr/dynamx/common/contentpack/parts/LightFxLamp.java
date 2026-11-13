package fr.dynamx.common.contentpack.parts;

import com.jme3.math.Vector3f;
import fr.dynamx.common.objloader.data.DxModelData;
import lombok.Getter;
import net.minecraft.util.math.MathHelper;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Une lampe d'un {@link PartLightSource} : sa position sur le modèle 3D et sa taille. <br>
 * Un objet de lumière contient souvent plusieurs lampes (une paire de phares, les modules d'une rampe) modélisées en
 * un seul objet, dont le centre n'appartient à aucune lampe. Les lampes sont donc extraites du mesh : ses sommets
 * sont découpés là où un vide les sépare, et chaque groupe obtenu est une lampe. <br>
 * Sert uniquement à placer les effets, voir fr.dynamx.client.renders.lights.DynamXLightFx.
 */
public class LightFxLamp {
    /**
     * Vide minimal, en blocs, entre deux groupes de sommets pour en faire deux lampes distinctes. En dessous, ce
     * sont deux parties d'une même lampe (modules d'une rampe, anneaux d'une optique).
     */
    private static final float MIN_LAMP_GAP = 0.12f;
    /**
     * Vides sur lesquels les sommets sont découpés, du plus large au plus étroit : le budget de
     * {@link LightFxLamp#MAX_LAMPS} lampes doit d'abord servir à séparer les groupes éloignés (la rampe à l'avant
     * du toit de celle à l'arrière) avant les modules à l'intérieur de l'un d'eux. Dans l'autre sens, il est
     * épuisé au milieu de la première rampe et le reste fusionne en une seule lampe couvrant tout le véhicule.
     */
    private static final float[] LAMP_GAPS = {1, MIN_LAMP_GAP};
    /**
     * Nombre de tranches par axe, pour chercher les vides entre les lampes
     */
    private static final int SLICES = 64;
    /**
     * Lampes max par objet : au-delà, les dernières sont fusionnées dans la dernière lampe
     */
    private static final int MAX_LAMPS = 8;
    /**
     * Sommets minimum pour qu'un groupe soit une lampe, ce qui écarte les sommets isolés d'un mesh
     */
    private static final int MIN_LAMP_VERTICES = 3;
    /**
     * Tronçons dans lesquels une lampe est découpée sur sa longueur, chacun sachant quelle partie de la texture y
     * est peinte. Les modules d'une rampe sont souvent une seule lampe (un seul bandeau de verre, sans vide à
     * découper) : lire la couleur de chaque tronçon permet à chaque module de briller dans la sienne.
     */
    public static final int COLOR_STRETCHES = 8;

    /**
     * Centre de la lampe, dans l'espace du modèle
     */
    @Getter
    private final Vector3f position;
    /**
     * Demi-dimensions de la lampe : taille du verre, dont dépendent la taille du halo et la largeur de départ du
     * faisceau
     */
    @Getter
    private final Vector3f halfSize;
    /**
     * Hauteur de la lampe sur son modèle : 0 en bas du véhicule, 1 en haut. Distingue un gyrophare de toit d'une
     * lampe de carrosserie ; c'est un ratio pour signifier la même chose sur une moto et sur un camion. NaN si le
     * modèle n'a pas pu être mesuré.
     */
    @Getter
    private float relativeHeight = Float.NaN;
    /**
     * Position longitudinale : 0 au milieu du véhicule, +0.5 à l'avant, -0.5 à l'arrière. Mesurée depuis le centre
     * du modèle et non son origine, que le modeleur place où il veut. NaN si le modèle n'a pas pu être mesuré.
     */
    @Getter
    private float relativeForward = Float.NaN;
    /**
     * Axe le plus long de la lampe : 0 pour x, 1 pour y, 2 pour z. Direction sur laquelle le halo est étalé et le
     * long de laquelle les tronçons sont lus, les deux partant de la plus petite coordonnée sur cet axe.
     */
    @Getter
    private final int longestAxis;
    /**
     * Partie de la texture peinte sur chaque tronçon, quatre floats par tronçon (u min, v min, u max, v max), d'une
     * extrémité de la lampe à l'autre le long de {@link LightFxLamp#longestAxis}. <br>
     * Null si le modèle n'a pas de coordonnées de texture ; un tronçon sans sommet garde ses u et v inversés.
     *
     * @see LightFxLamp#COLOR_STRETCHES
     */
    @Getter
    private final float[] uvStretches;

    public LightFxLamp(Vector3f position, Vector3f halfSize) {
        this(position, halfSize, null);
    }

    public LightFxLamp(Vector3f position, Vector3f halfSize, @Nullable float[] uvStretches) {
        this.position = position;
        this.halfSize = halfSize;
        this.uvStretches = uvStretches;
        this.longestAxis = longestAxisOf(halfSize);
    }

    /**
     * @return L'axe le plus long de ces demi-dimensions : 0 pour x, 1 pour y, 2 pour z
     */
    public static int longestAxisOf(Vector3f halfSize) {
        if (halfSize.x >= halfSize.y && halfSize.x >= halfSize.z) {
            return 0;
        }
        return halfSize.y >= halfSize.z ? 1 : 2;
    }

    /**
     * Mesure la position de la lampe sur son modèle, en ratios de la taille du modèle plutôt qu'en distances
     *
     * @param modelMin Coin le plus bas du modèle entier
     * @param modelMax Coin le plus haut du modèle entier
     * @see LightFxLamp#relativeHeight
     * @see LightFxLamp#relativeForward
     */
    public void measureOn(Vector3f modelMin, Vector3f modelMax) {
        float heightSpan = modelMax.y - modelMin.y;
        if (heightSpan > 1e-3f) {
            relativeHeight = (position.y - modelMin.y) / heightSpan;
        }
        float lengthSpan = modelMax.z - modelMin.z;
        if (lengthSpan > 1e-3f) {
            relativeForward = (position.z - (modelMin.z + modelMax.z) / 2) / lengthSpan;
        }
    }

    /**
     * Amène la lampe dans l'espace où la part de lumière est placée
     */
    public void scale(Vector3f scaleModifier) {
        position.multLocal(scaleModifier);
        halfSize.multLocal(scaleModifier);
    }

    /**
     * Lit les lampes contenues dans un objet de lumière
     *
     * @param modelData  Le modèle 3D portant la lumière
     * @param objectName Le nom de l'objet 3D de la lumière
     * @return Les lampes de l'objet, sans ordre particulier, vide si l'objet n'est pas dans le modèle
     */
    public static List<LightFxLamp> read(DxModelData modelData, @Nullable String objectName) {
        if (objectName == null || !modelData.getMeshNames().contains(objectName.toLowerCase())) {
            return Collections.emptyList();
        }
        // Les sommets d'un modèle gltf sont stockés dans l'espace de leur noeud : on les ramène dans l'espace du
        // modèle pour découper, placer et mesurer les lampes là où elles sont réellement dessinées. Ceux d'un obj
        // y sont déjà et sont laissés tels quels.
        float[] transform = modelData.getMeshTransform(objectName);
        float[] vertices = modelData.getVerticesPos(objectName);
        if (vertices.length < 3 * MIN_LAMP_VERTICES) {
            // Mesh illisible : tout l'objet devient une seule lampe
            return Collections.singletonList(new LightFxLamp(
                    transformPoint(transform, modelData.getMeshCenter(objectName, new Vector3f())),
                    modelData.getMeshDimension(objectName, new Vector3f())));
        }
        transformVertices(transform, vertices);
        // Coordonnées de texture, d'où est lue la couleur de chaque lampe et de chaque tronçon. Écartées s'il n'y
        // en a pas exactement une par sommet : le tableau ne correspondrait pas à celui des positions.
        float[] texCoords = modelData.getVerticesTexCoords(objectName);
        if (texCoords.length * 3 != vertices.length * 2) {
            texCoords = null;
        }
        // Découpage axe par axe, en largeur (sépare une paire de phares), puis en longueur, puis en hauteur, et
        // ainsi de suite jusqu'à ce qu'une passe complète ne trouve plus rien : un découpage ne voit l'objet que
        // tel qu'il est, et la paire de feux de calandre n'apparaît qu'une fois la rampe de toit détachée en
        // longueur. Vides les plus larges d'abord, voir LAMP_GAPS.
        int[] groups = new int[vertices.length / 3];
        int groupCount = 1;
        for (float minGap : LAMP_GAPS) {
            int before;
            do {
                before = groupCount;
                for (int axis = 0; axis < 3; axis++) {
                    groupCount = splitOnGaps(vertices, groups, groupCount, axis == 0 ? 0 : axis == 1 ? 2 : 1,
                            minGap);
                }
            } while (groupCount > before && groupCount < MAX_LAMPS);
        }
        return buildLamps(vertices, texCoords, groups, groupCount);
    }

    /**
     * Mesure la position de chaque lampe sur le modèle entier. <br>
     * Les limites du modèle sont mesurées ici plutôt que lues dans {@link DxModelData#getMinOfModel(Vector3f)} :
     * celles-ci sont les limites brutes des sommets de tous les objets, et sur un gltf, où chaque objet est stocké
     * autour de l'origine de son noeud, elles ne ressemblent pas au véhicule.
     *
     * @param lamps Les lampes à mesurer, laissées telles quelles si le modèle n'est pas mesurable
     * @see LightFxLamp#relativeHeight
     * @see LightFxLamp#relativeForward
     */
    public static void measureOnModel(DxModelData modelData, List<LightFxLamp> lamps) {
        if (lamps.isEmpty()) {
            return;
        }
        Vector3f min = new Vector3f(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE);
        Vector3f max = new Vector3f(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE);
        boolean measured = false;
        for (String meshName : modelData.getMeshNames()) {
            if (meshName.equalsIgnoreCase("main")) {
                // Jamais dessiné, voir ObjModelRenderer#renderGroup : l'inclure mesurerait les lampes contre un
                // modèle qui n'est pas celui affiché
                continue;
            }
            Vector3f meshMin = modelData.getMeshMin(meshName, new Vector3f());
            Vector3f meshMax = modelData.getMeshMax(meshName, new Vector3f());
            if (meshMin == null || meshMax == null) {
                continue;
            }
            // Les huit coins, et pas seulement les deux donnés : la transformation de l'objet peut le tourner
            float[] transform = modelData.getMeshTransform(meshName);
            for (int corner = 0; corner < 8; corner++) {
                Vector3f at = transformPoint(transform, new Vector3f(
                        (corner & 1) == 0 ? meshMin.x : meshMax.x,
                        (corner & 2) == 0 ? meshMin.y : meshMax.y,
                        (corner & 4) == 0 ? meshMin.z : meshMax.z));
                min.set(Math.min(min.x, at.x), Math.min(min.y, at.y), Math.min(min.z, at.z));
                max.set(Math.max(max.x, at.x), Math.max(max.y, at.y), Math.max(max.z, at.z));
            }
            measured = true;
        }
        if (!measured) {
            return;
        }
        for (LightFxLamp lamp : lamps) {
            lamp.measureOn(min, max);
        }
    }

    /**
     * Découpe les groupes de sommets sur un axe, partout où un vide assez large les traverse. Les sommets sont
     * rangés en tranches le long de l'axe, et une suite de tranches vides plus large que minGap termine un groupe.
     * Le premier groupe d'un découpage garde l'identifiant d'origine, les autres en reçoivent de nouveaux.
     *
     * @param groups     Le groupe de chaque sommet, lu et mis à jour
     * @param groupCount Le nombre de groupes actuels
     * @param axis       L'axe de découpage : 0 pour x, 1 pour y, 2 pour z
     * @param minGap     Le vide minimal, en blocs, séparant deux lampes
     * @return Le nombre de groupes après découpage
     */
    private static int splitOnGaps(float[] vertices, int[] groups, int groupCount, int axis, float minGap) {
        int[] counts = new int[SLICES];
        int[] sliceGroups = new int[SLICES];
        int total = groupCount;
        for (int group = 0; group < groupCount && total < MAX_LAMPS; group++) {
            float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
            for (int vertex = 0; vertex < groups.length; vertex++) {
                if (groups[vertex] == group) {
                    float value = vertices[vertex * 3 + axis];
                    min = Math.min(min, value);
                    max = Math.max(max, value);
                }
            }
            float extent = max - min;
            if (extent <= minGap) {
                // Trop petit sur cet axe pour contenir deux lampes côte à côte
                continue;
            }
            Arrays.fill(counts, 0);
            for (int vertex = 0; vertex < groups.length; vertex++) {
                if (groups[vertex] == group) {
                    counts[sliceOf(vertices[vertex * 3 + axis], min, extent)]++;
                }
            }
            // Tranches vides consécutives faisant un vide entre deux lampes, et non un trou dans une lampe
            int gapSlices = Math.max(1, MathHelper.ceil(minGap * SLICES / extent));
            int subGroups = 0, empty = SLICES;
            for (int slice = 0; slice < SLICES; slice++) {
                if (counts[slice] == 0) {
                    empty++;
                    sliceGroups[slice] = -1;
                } else {
                    if (empty >= gapSlices) {
                        subGroups++;
                    }
                    empty = 0;
                    sliceGroups[slice] = subGroups - 1;
                }
            }
            if (subGroups <= 1) {
                continue;
            }
            // Ce qui ne tient pas dans le budget de lampes rejoint la dernière de ce découpage
            int room = Math.min(subGroups, MAX_LAMPS - total + 1);
            for (int vertex = 0; vertex < groups.length; vertex++) {
                if (groups[vertex] != group) {
                    continue;
                }
                int subGroup = sliceGroups[sliceOf(vertices[vertex * 3 + axis], min, extent)];
                if (subGroup > 0) {
                    groups[vertex] = total + Math.min(subGroup, room - 1) - 1;
                }
            }
            total += room - 1;
        }
        return total;
    }

    /**
     * Construit une lampe par groupe de sommets, en écartant les groupes trop petits
     *
     * @param texCoords Coordonnées de texture de chaque sommet, deux floats chacune, ou null : les lampes sont
     *                  alors construites sans leurs tronçons
     */
    private static List<LightFxLamp> buildLamps(float[] vertices, @Nullable float[] texCoords, int[] groups,
                                                int groupCount) {
        List<LightFxLamp> lamps = new ArrayList<>(groupCount);
        for (int group = 0; group < groupCount; group++) {
            int found = 0;
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
            for (int vertex = 0; vertex < groups.length; vertex++) {
                if (groups[vertex] != group) {
                    continue;
                }
                found++;
                float x = vertices[vertex * 3], y = vertices[vertex * 3 + 1], z = vertices[vertex * 3 + 2];
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                minZ = Math.min(minZ, z);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
                maxZ = Math.max(maxZ, z);
            }
            if (found < MIN_LAMP_VERTICES) {
                continue;
            }
            Vector3f center = new Vector3f((minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2);
            Vector3f halfSize = new Vector3f((maxX - minX) / 2, (maxY - minY) / 2, (maxZ - minZ) / 2);
            lamps.add(new LightFxLamp(center, halfSize,
                    readUvStretches(vertices, texCoords, groups, group, halfSize, minX, minY, minZ)));
        }
        return lamps;
    }

    /**
     * Lit la partie de texture peinte sur chaque tronçon d'une lampe : ses sommets sont rangés le long de sa
     * longueur, et les coordonnées de texture de chaque casier sont celles de ce tronçon. Rangés le long de la
     * longueur et non lus dans l'ordre du mesh, qui n'en a aucun, et parce que le halo est dessiné sur cette même
     * longueur.
     *
     * @param group    Le groupe de sommets de cette lampe
     * @param halfSize Les demi-dimensions, qui donnent le sens de la longueur
     * @return Les coordonnées de chaque tronçon, quatre floats chacun, ou null si le modèle n'en porte pas
     * @see LightFxLamp#uvStretches
     */
    @Nullable
    private static float[] readUvStretches(float[] vertices, @Nullable float[] texCoords, int[] groups, int group,
                                           Vector3f halfSize, float minX, float minY, float minZ) {
        if (texCoords == null) {
            return null;
        }
        int axis = longestAxisOf(halfSize);
        float min = axis == 0 ? minX : axis == 1 ? minY : minZ;
        float extent = 2 * (axis == 0 ? halfSize.x : axis == 1 ? halfSize.y : halfSize.z);
        float[] stretches = new float[COLOR_STRETCHES * 4];
        for (int stretch = 0; stretch < COLOR_STRETCHES; stretch++) {
            // Laissés inversés, ce qui distingue un tronçon sans sommet d'un vrai tronçon
            stretches[stretch * 4] = Float.MAX_VALUE;
            stretches[stretch * 4 + 1] = Float.MAX_VALUE;
            stretches[stretch * 4 + 2] = -Float.MAX_VALUE;
            stretches[stretch * 4 + 3] = -Float.MAX_VALUE;
        }
        for (int vertex = 0; vertex < groups.length; vertex++) {
            if (groups[vertex] != group) {
                continue;
            }
            int stretch = extent <= 1e-5f ? 0 : MathHelper.clamp(
                    (int) ((vertices[vertex * 3 + axis] - min) / extent * COLOR_STRETCHES), 0, COLOR_STRETCHES - 1);
            float u = texCoords[vertex * 2], v = texCoords[vertex * 2 + 1];
            int at = stretch * 4;
            stretches[at] = Math.min(stretches[at], u);
            stretches[at + 1] = Math.min(stretches[at + 1], v);
            stretches[at + 2] = Math.max(stretches[at + 2], u);
            stretches[at + 3] = Math.max(stretches[at + 3], v);
        }
        return stretches;
    }

    /**
     * @return La tranche où tombe cette coordonnée, parmi les {@link LightFxLamp#SLICES} tranches de l'étendue
     */
    private static int sliceOf(float value, float min, float extent) {
        return MathHelper.clamp((int) ((value - min) / extent * SLICES), 0, SLICES - 1);
    }

    /**
     * Amène sur place chaque sommet d'un mesh dans l'espace de son modèle
     *
     * @param transform La transformation du mesh, null s'il n'y en a pas
     * @param vertices  Les sommets, trois floats chacun
     */
    private static void transformVertices(@Nullable float[] transform, float[] vertices) {
        if (transform == null) {
            return;
        }
        for (int vertex = 0; vertex < vertices.length / 3; vertex++) {
            int at = vertex * 3;
            float x = vertices[at], y = vertices[at + 1], z = vertices[at + 2];
            vertices[at] = transform[0] * x + transform[4] * y + transform[8] * z + transform[12];
            vertices[at + 1] = transform[1] * x + transform[5] * y + transform[9] * z + transform[13];
            vertices[at + 2] = transform[2] * x + transform[6] * y + transform[10] * z + transform[14];
        }
    }

    /**
     * Amène sur place un point dans l'espace de son modèle
     *
     * @param transform La transformation du mesh, matrice 4x4 en colonnes, null s'il n'y en a pas
     * @return Le point transformé
     */
    private static Vector3f transformPoint(@Nullable float[] transform, Vector3f point) {
        if (transform == null) {
            return point;
        }
        return point.set(
                transform[0] * point.x + transform[4] * point.y + transform[8] * point.z + transform[12],
                transform[1] * point.x + transform[5] * point.y + transform[9] * point.z + transform[13],
                transform[2] * point.x + transform[6] * point.y + transform[10] * point.z + transform[14]);
    }
}
