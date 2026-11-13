package fr.dynamx.client.renders.lights;

import com.jme3.math.Vector3f;
import fr.dynamx.client.handlers.ClientEventHandler;
import fr.dynamx.client.renders.scene.BaseRenderContext;
import fr.dynamx.client.renders.scene.IRenderContext;
import fr.dynamx.common.DynamXMain;
import fr.dynamx.common.contentpack.parts.LightFxLamp;
import fr.dynamx.common.contentpack.parts.LightObject;
import fr.dynamx.common.contentpack.parts.PartLightSource;
import fr.dynamx.utils.DynamXConfig;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import javax.annotation.Nullable;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Dessine les effets des lumières allumées : le halo des lampes, les faisceaux balayants d'un gyrophare et la
 * lumière qu'un phare pose sur la route et les murs devant lui. Un phare ne dessine pas de faisceau, un cône d'air
 * éclairé se lisant comme une forme solide collée à la carrosserie. <br>
 * La lumière s'arrête au premier obstacle (bloc, véhicule ou joueur), mais rien n'est jamais dessiné <em>sur</em> un
 * véhicule ni un joueur. <br>
 * Côté client : les lumières sont collectées par {@link PartLightSource} pendant le rendu des entités et des blocs,
 * puis dessinées en une passe additive en fin d'image depuis {@link ClientEventHandler#renderWorldLast}, qui rend
 * l'état open gl tel qu'il l'a trouvé. Réglable via <code>/client_dynamx lightfx</code> et la config DynamX.
 */
@SideOnly(Side.CLIENT)
public class DynamXLightFx {
    private static final int SEGMENTS = 14;
    /** Sections le long d'un faisceau et leur alpha : fin et vif à la lampe, puis élargi et estompé */
    private static final float[] BEAM_STEPS = {0, 0.45f, 1};
    private static final float[] BEAM_STEP_ALPHAS = {1, 0.4f, 0};
    /** Distance, en blocs, au-delà de laquelle un côté de section de faisceau ne vaut plus d'être dessiné */
    private static final float CONE_SEGMENT_DISTANCE = 6;
    /** Décalage, en blocs, de la lumière posée sur une surface, pour éviter le z-fighting */
    private static final float SURFACE_OFFSET = 0.02f;
    private static final float MAX_BEAM_LENGTH = 30;
    private static final float MAX_POOL_RADIUS = 18;

    /* Projection d'un phare sur le sol devant lui : posée bloc par bloc sur le dessus du terrain, pour qu'elle
       grimpe les marches et les pentes, descende dans les trous et s'arrête où le sol s'arrête. */

    /** Distance, en blocs, jusqu'à laquelle les surfaces d'un projecteur sont cherchées avec tous les rayons */
    private static final float SPOT_FINE_DISTANCE = 16;
    /** Profondeur de recherche du sol en plus de la portée : le sol devant un véhicule sur un pont est loin dessous */
    private static final int GROUND_EXTRA_DROP = 10;
    /** Hauteur, en blocs, au-dessus du sol du bloc précédent où chercher le suivant : marches et pentes, pas un mur */
    private static final int GROUND_SEARCH_RISE = 2;
    /** Décalage, en blocs, sous la surface trouvée, pour retrouver le bloc auquel elle appartient */
    private static final float GROUND_SURFACE_BIAS = 0.01f;
    /**
     * Écart, en blocs, dans lequel un point compte comme de niveau avec le sol sous la lampe : la lumière l'atteint
     * forcément, ce qui économise un rayon par coin de bloc d'une route
     */
    private static final float GROUND_LEVEL_TOLERANCE = 0.05f;
    /** Demi-diagonale du dessus d'un bloc : distance max de son milieu à n'importe laquelle de ses parties */
    private static final float BLOCK_HALF_DIAGONAL = 0.7072f;
    /**
     * Marge, en blocs, au-delà du bord du faisceau où la lumière est encore calculée aux coins des blocs : le
     * dégradé vient d'une texture, mais un coin ne porte que ce qui vaut pour tout le bloc, et pour que le dégradé
     * finisse sur le bord du faisceau les coins doivent en porter de part et d'autre
     */
    private static final float PROFILE_MARGIN = BLOCK_HALF_DIAGONAL * 2;
    /**
     * Bord du faisceau qu'un point dépasse, bits de {@link DynamXLightFx#profileSide}. Une face dont les quatre
     * coins dépassent le même bord ne reçoit aucune lumière de ce faisceau.
     */
    private static final int PROFILE_PAST_HIGH = 1, PROFILE_PAST_LOW = 2, PROFILE_BEFORE_START = 4,
            PROFILE_PAST_END = 8, PROFILE_PAST_TOP = 16, PROFILE_PAST_BOTTOM = 32;
    private static final int PROFILE_ALL_SIDES = PROFILE_PAST_HIGH | PROFILE_PAST_LOW | PROFILE_BEFORE_START
            | PROFILE_PAST_END | PROFILE_PAST_TOP | PROFILE_PAST_BOTTOM;
    /** Où est prise la surface d'un bloc d'eau ou de lave : sans boîte propre, mais ils reçoivent la lumière */
    private static final AxisAlignedBB LIQUID_SURFACE_BOX = new AxisAlignedBB(0, 0, 0, 1, 0.9, 1);
    /** Demi-largeur du faisceau au bout, en multiple du rayon au sol ; au départ c'est la taille de la lampe */
    private static final float SPOT_WIDTH_FAR = 0.7f;
    /** Demi-largeur minimale d'une lampe, pour un objet plat ou dont la taille n'a pas pu être lue */
    private static final float SPOT_MIN_LAMP_HALF_WIDTH = 0.12f;
    /**
     * Largeur max du faisceau à la lampe, en fraction de celle du bout : le dégradé est mesuré depuis le point où
     * ses bords se rejoignent, qui recule à l'infini si le faisceau ne s'évase presque pas
     */
    private static final float SPOT_MAX_NEAR_RATIO = 0.3f;
    /** Fraction de la projection sur laquelle la lumière monte, juste devant la lampe */
    private static final float SPOT_NEAR_RAMP = 0.1f;
    /**
     * Début de la lumière au sol, en fraction de l'empreinte mesurée comme le dégradé la mesure : un phare éclaire
     * la route devant le véhicule et non le sol sous son pare-chocs. En fraction et non en blocs, car c'est cuit
     * dans le dégradé, qui ignore la taille de chaque lumière.
     */
    private static final float SPOT_GROUND_START = 0.1f;
    /**
     * Projecteurs dont la projection est calculée par image, les plus proches d'abord ; les autres gardent le halo
     * de leur lampe
     */
    private static final int MAX_PROJECTED_SPOTS = 16;
    /** Rang à partir duquel les projections sont atténuées, pour que celle qui ne tient pas soit déjà éteinte */
    private static final int PROJECTION_FADE_FROM = MAX_PROJECTED_SPOTS - MAX_PROJECTED_SPOTS / 4;
    /** Entités prises comme obstacles pour toute l'image, cherchées une fois et partagées par ses lumières */
    private static final int MAX_FRAME_OCCLUDERS = 96;
    /** Leurs boîtes, en min x, y, z puis max x, y, z, avec l'identifiant de l'entité et de sa monture */
    private static final double[] FRAME_OCCLUDERS = new double[MAX_FRAME_OCCLUDERS * 6];
    private static final int[] FRAME_OCCLUDER_IDS = new int[MAX_FRAME_OCCLUDERS],
            FRAME_OCCLUDER_RIDING = new int[MAX_FRAME_OCCLUDERS];
    private static int frameOccluderCount;
    /**
     * Points de lumière calculés à l'intérieur des faces, en plus de leurs coins, distribués aux lumières les plus
     * proches d'abord. Chacun peut coûter un rayon : c'est ce qui borne le coût d'une zone pleine de véhicules.
     */
    private static final int MAX_FRAME_FACE_SAMPLES = 8192;
    private static int frameFaceSamplesLeft;
    /** Budget distribué au départ de l'image, auquel {@link DynamXLightFx#FACE_SAMPLE_TAPER} est rapporté */
    private static int frameFaceSampleBudget;
    /** Faces dressées dans les faisceaux éclairées par l'image, et minimum garanti à chaque lumière */
    private static final int MAX_FRAME_SURFACE_FACES = 1024, MIN_SURFACE_FACES = 24;
    private static int frameSurfaceFacesLeft;
    /** Rayons lancés pour trouver les surfaces : l'éventail d'un projecteur est ce qu'une lumière fait de plus cher */
    private static final int MAX_FRAME_SEED_RAYS = 1024;
    private static int frameSeedRaysLeft;
    /** Colonnes de blocs où l'image cherche le sol : un projecteur en couvre toute l'empreinte de son faisceau */
    private static final int MAX_FRAME_GROUND_COLUMNS = 6144;
    private static int frameGroundColumnsLeft;
    /**
     * Distances de détail de l'image, mises à l'échelle du réglage de distance et gardées sous la forme où elles
     * sont testées (inverse ou carré), voir {@link DynamXLightFx#computeDetailDistances}
     */
    private static float frameFaceStepFactor, frameConeSegmentFactor;
    private static double frameSpotFineDistanceSq, frameBeamTraceDistanceSq;
    /** Taille min, en blocs, d'une entité qui arrête la lumière : en dessous c'est un objet ou un projectile */
    private static final float MIN_OCCLUDER_SIZE = 0.4f;
    /** Recul max, en blocs, du point d'émission derrière le milieu de la lampe */
    private static final float MAX_EMITTER_OFFSET = 0.35f;
    /**
     * Rayons lancés en travers et en hauteur du faisceau pour trouver les surfaces : ils n'ont qu'à les trouver,
     * la lumière est ensuite étalée sur la face touchée puis sur ses voisines
     */
    private static final int SPOT_SEED_ACROSS = 9, SPOT_SEED_UP = 7;
    /** Visée de ces rayons dans la section du faisceau, en fraction de sa demi-taille : en deçà du bord */
    private static final float SPOT_SEED_SPREAD = 0.85f;
    /**
     * Élargissement imposé à l'éventail, en multiple de {@link DynamXLightFx#SPOT_MIN_SURFACE_SPREAD} : à la
     * largeur du bout du faisceau, il ne ferait qu'un bloc de large à quelques blocs. Les rayons tombant hors du
     * faisceau sont écartés sans rien coûter.
     */
    private static final float SPOT_SEED_REACH = 4;
    /** Faces qu'un projecteur éclaire par image ; celles qu'il vise tiennent toujours dans ce budget */
    private static final int MAX_LIT_FACES = 192;
    /**
     * Dessus de blocs sur lesquels un projecteur pose sa lumière au sol par image, un par bloc : dimensionné pour
     * l'empreinte du plus large faisceau possible, et donc filet de sécurité plutôt que vraie limite
     */
    private static final int MAX_GROUND_FACES = 640;
    /** Faces couvertes par la tache d'un faisceau : un gyrophare en pose une par faisceau, large de peu de blocs */
    private static final int MAX_PATCH_FACES = 64;
    /** Taches posées au bout des faisceaux par image, toutes lumières confondues, les plus proches d'abord */
    private static final int MAX_BEAM_PATCHES = 16;
    /** Distance, en blocs, au-delà de laquelle un faisceau n'est plus arrêté : pleine longueur et sans tache */
    private static final float BEAM_TRACE_DISTANCE = 32;
    /** Tache minimale, en blocs : plus petite, elle tomberait entre les coins de la face touchée */
    private static final float MIN_PATCH_RADIUS = 1;
    /** Demi-taille min du faisceau sur une surface : plus étroit, il n'éclairerait aucun coin de la face */
    private static final float SPOT_MIN_SURFACE_SPREAD = 1.25f;
    /** Fin de rayon ignorée, en blocs, sur une surface : testée au millimètre, une face s'ombrerait elle-même */
    private static final float SURFACE_SHADOW_TOLERANCE = 0.35f;
    /** Idem pour un point de sol, plus court : ces rayons rasent le sol, ce qui les ombre est là où ils tombent */
    private static final float GROUND_SHADOW_TOLERANCE = 0.2f;
    /**
     * Largeur, en blocs, du bord adouci de l'ombre d'une entité. Ombrée sur sa bounding box, qui bouge à chaque
     * image : en tout ou rien, la lumière autour d'elle clignoterait bloc par bloc.
     */
    private static final float SHADOW_SOFTNESS = 0.75f;
    /**
     * Sous-quads en lesquels une face est découpée par axe, ce qui donne la finesse de la lumière posée dessus :
     * dessinée d'un seul quad, une face fait tomber un bord d'ombre ou de faisceau sur ses coins
     */
    private static final int MAX_FACE_STEPS = 4;
    /** Crans de subdivision : le maximum, atteint par les faces les plus proches de la caméra */
    private static final int MAX_DETAIL = MAX_FACE_STEPS - 1;
    /** Distance, en blocs, qui vaut un cran de subdivision : une face est découpée autant qu'elle est proche */
    private static final float FACE_STEP_DISTANCE = 9;
    /**
     * Réglage de distance, en blocs, pour lequel toutes les distances de détail sont écrites. Elles sont mises à
     * l'échelle du réglage réel, qui se lit comme "jusqu'où les effets doivent tenir".
     */
    private static final float REFERENCE_MAX_DISTANCE = 64;
    private static final float MIN_DETAIL_SCALE = 0.5f, MAX_DETAIL_SCALE = 3;
    /**
     * Fraction des points de lumière de l'image sur laquelle la subdivision est abandonnée cran par cran : les
     * faces sont posées dans l'ordre des blocs, et un budget qui s'arrête net tranche une ligne visible
     */
    private static final float FACE_SAMPLE_TAPER = 0.25f;
    /** En dessous de cet écart entre coins d'une face, elle est dessinée d'un seul quad : lumière déjà uniforme */
    private static final float FACE_FLAT_TOLERANCE = 0.08f;
    private static final int MAX_TRACE_STEPS = 160;
    /** Inclinaison vers le bas d'un projecteur dont la direction est devinée, pour éclairer la route au loin */
    private static final float SPOT_TILT = 0.06f;
    private static final float SPOT_THROW = 2.5f;
    /** Aplatissement de la lumière d'un projecteur : un phare s'étale bien plus en largeur qu'en hauteur */
    private static final float SPOT_FLATTEN = 0.45f;
    /** Avance min, en blocs, d'une lampe fixe devant le centre du modèle pour être devinée projecteur */
    private static final float AUTO_SPOT_MIN_Z = 0.15f;
    /** Distance max au milieu du modèle pour être pris pour une rampe de toit : une lampe de carrosserie est au bout */
    private static final float BEACON_MAX_RELATIVE_OFFSET = 0.35f;
    /**
     * Et sa hauteur min. Basse exprès : un gyrophare n'est pas plus haut que les feux arrière d'un camion, ce qui
     * les distingue est d'être au milieu ou non.
     */
    private static final float BEACON_MIN_RELATIVE_HEIGHT = 0.5f;
    /** Avance min vers l'avant, en fraction de la longueur du modèle, pour être pris pour un projecteur */
    private static final float AUTO_SPOT_MIN_FORWARD = 0.2f;
    /** Lumières max collectées par image, filet de sécurité si une image se termine sans être dessinée */
    private static final int HARD_LIGHT_LIMIT = 512;
    /**
     * Marge, en blocs, sur la distance de collecte : les lumières sont filtrées sur leur distance à l'entité de vue
     * mais atténuées sur celle à la caméra, qui s'en écarte en vue troisième personne
     */
    private static final int VIEWER_CULL_SLACK = 16;
    private static final float SURFACE_ALPHA = 0.32f;
    /** Force de la lumière dans l'air (faisceaux) : faible, c'est un volume de poussière éclairée */
    private static final float CONE_ALPHA = 0.1f;
    private static final float FLARE_ALPHA = 0.4f;
    /** Taille du halo, en multiple de celle de la lampe et non de la portée */
    private static final float FLARE_SIZE = 1.5f;
    /**
     * Halo minimal et maximal, en blocs. Plafond bas exprès : les packs modélisent souvent tous les feux d'un bout
     * de véhicule en un seul objet de deux blocs de large.
     */
    private static final float MIN_FLARE_SIZE = 0.1f, MAX_FLARE_SIZE = 0.4f;
    /**
     * Élargissement du halo par bloc de distance, pour qu'il reste visible de loin. La lumière ajoutée est reprise
     * sur sa force, pour qu'élargir ne fasse qu'étaler la lumière de la lampe.
     */
    private static final float FLARE_GROWTH = 0.012f;
    /** Halos max sur la longueur d'une lampe : au-delà ils sont espacés, à coût constant */
    private static final int MAX_FLARE_STEPS = 6;
    /**
     * Début, fin et nombre de halos de chaque suite de tronçons d'une même couleur, gardés ici plutôt que
     * reconstruits pour chaque lampe visible
     */
    private static final int[] FLARE_RUN_START = new int[LightFxLamp.COLOR_STRETCHES],
            FLARE_RUN_END = new int[LightFxLamp.COLOR_STRETCHES],
            FLARE_RUN_STEPS = new int[LightFxLamp.COLOR_STRETCHES];
    /** Force gardée par chaque halo d'une rangée : ils se chevauchent et s'additionneraient au-delà de la lampe */
    private static final float FLARE_OVERLAP_DAMPING = 0.75f;
    /** Taille du coeur du halo, en multiple du halo : c'est la part qui se lit comme le verre allumé */
    private static final float FLARE_CORE_SIZE = 0.75f;
    /**
     * Taille et force du voile autour de ce coeur. Gardé près de la lampe : plus large, il flotte à côté du
     * véhicule.
     */
    private static final float FLARE_BLOOM_SIZE = 1.5f, FLARE_BLOOM_ALPHA = 0.22f;
    /** Avance du halo vers la caméra, en blocs, pour que la lampe ne le cache pas. Petite, sinon il flotte. */
    private static final float FLARE_LIFT = 0.08f;
    /** Halo gardé par un projecteur vu de dos : un phare vu de l'arrière reste visiblement allumé */
    private static final float SPOT_FLARE_FROM_BEHIND = 0.55f;
    /** Largeur, en radians, du flash de la lampe d'un gyrophare rotatif vu de la caméra */
    private static final float FLARE_SWEEP_SPREAD = 0.55f;
    /**
     * Force et taille du halo d'un gyrophare face à une lampe ordinaire : ses faisceaux étant faibles, c'est le
     * halo qui porte l'effet
     */
    private static final float BEACON_FLARE_BOOST = 2.2f, BEACON_FLARE_SIZE = 1.7f;
    /** Halo gardé quand les miroirs d'un gyrophare sont de dos : bas, car tout l'effet est la pulsation */
    private static final float BEACON_FLARE_FLOOR = 0.22f;
    /**
     * Une lampe plus proche que NEAR_FADE_END n'est pas dessinée, et une plus proche que NEAR_FADE_START est
     * atténuée : sinon un billboard additif couvrirait tout l'écran en première personne
     */
    private static final float NEAR_FADE_END = 0.9f, NEAR_FADE_START = 2.5f;
    /** Idem pour les faisceaux, bien plus court pour que le joueur voie encore ceux de son véhicule */
    private static final float BEAM_FADE_END = 0.4f, BEAM_FADE_START = 1.4f;
    /** Force gardée en plein jour : une lampe allumée et un gyrophare restent bien visibles */
    private static final float DAYLIGHT_FLOOR = 0.25f;
    /**
     * Idem pour un projecteur : le jour efface la lumière posée sur la route, et les phares ne prennent le relais
     * qu'au coucher du soleil
     */
    private static final float SPOT_DAYLIGHT_FLOOR = 0.05f;
    /** Puissance appliquée à l'obscurité : un projecteur reste discret l'après-midi et monte au crépuscule */
    private static final float SPOT_DARKNESS_CURVE = 2.5f;
    /**
     * Les huit heures où le soleil est au plus haut, en temps du monde (10:00 à 18:00). Les lumières ne posent
     * alors rien sur le monde et seules les lampes brillent, voir {@link DynamXLightFx#glowsOnly}.
     */
    private static final long HIGH_SUN_FROM = 4000, HIGH_SUN_TO = 12000;
    /** En dessous, une lumière ou une surface est trop faible pour valoir d'être dessinée */
    private static final float MIN_VISIBLE = 0.01f;

    /** Lumières soumises pendant cette image, recyclées : seules les {@link DynamXLightFx#count} premières valent */
    private static final List<LightFxEntry> ENTRIES = new ArrayList<>();
    private static int count;
    /** Les plus proches de la caméra d'abord, ordre dans lequel tous les budgets sont distribués */
    private static final Comparator<LightFxEntry> BY_CAMERA_DISTANCE =
            Comparator.comparingDouble((LightFxEntry entry) -> entry.cameraDistanceSq)
                    // Deux lampes côte à côte sont à la même distance au bit près, et l'ordre de collecte change
                    // d'une image à l'autre : départagées par leur clé, les budgets vont toujours aux mêmes lampes
                    .thenComparingInt(entry -> entry.stableKey);
    /**
     * Lumières déjà soumises cette image : certaines configurations (shaders, mods) rendent la même entité deux
     * fois, et les effets additifs seraient deux fois plus forts
     */
    private static final int SUBMITTED_MASK = 2047;
    private static final int[] SUBMITTED_KEYS = new int[SUBMITTED_MASK + 1];
    private static final int[] SUBMITTED_STAMPS = new int[SUBMITTED_MASK + 1];
    /** Lampes notées cette image, pour que la table ne se remplisse jamais (elle serait parcourue sans fin) */
    private static int submittedCount;
    /** Marque avec laquelle cette table a été écrite cette image */
    private static int submittedStamp = -1;

    /**
     * Position du monde sur laquelle le rendu est centré : l'entité de vue interpolée et <strong>pas</strong> la
     * caméra. Les sommets sont donnés par rapport à elle.
     */
    private static double originX, originY, originZ;
    /**
     * Position de la caméra, plusieurs blocs derrière {@link DynamXLightFx#originX} en vue troisième personne.
     * Tout ce qui dépend du point de vue (taille et fondu des halos, flash des gyrophares) doit l'utiliser.
     */
    private static double cameraX, cameraY, cameraZ;

    private static final FloatBuffer MODELVIEW_BUFFER = BufferUtils.createFloatBuffer(16);
    private static final FloatBuffer PROJECTION_BUFFER = BufferUtils.createFloatBuffer(16);
    /** Axes de la caméra en espace monde, pour construire les billboards des halos */
    private static float rightX, rightY, rightZ, upX, upY, upZ;
    /**
     * Les six côtés du champ de vision, en plans de quatre nombres (normale vers l'intérieur et distance). Lus dans
     * les matrices de l'image en cours, voir {@link DynamXLightFx#readFrustum}, et non pris au frustum du monde,
     * qui appartient au rendu des entités, déjà terminé.
     */
    private static final float[] FRUSTUM_PLANES = new float[6 * 4];
    /** Projection et modelview multipliées, en lignes, d'où ces plans sont lus */
    private static final float[] VIEW_PROJECTION = new float[16];

    /* Faces des blocs sur lesquelles une lumière est posée, collectées par lumière et par image. Elles sont
       parcourues depuis celles où le faisceau a atterri, d'où la file. */

    private static int litFaceCount;
    /** Taches que les faisceaux de cette image peuvent encore poser, aux plus proches d'abord */
    private static int beamPatchesLeft;
    /** Place donnée aux tables ci-dessous : le plus grand des budgets qu'une lumière peut recevoir */
    private static final int MAX_FACE_SLOTS = Math.max(MAX_LIT_FACES, Math.max(MAX_GROUND_FACES, MAX_PATCH_FACES));
    private static final int[] FACE_BLOCK_X = new int[MAX_FACE_SLOTS], FACE_BLOCK_Y = new int[MAX_FACE_SLOTS],
            FACE_BLOCK_Z = new int[MAX_FACE_SLOTS];
    /** Face de son bloc, en {@link EnumFacing#ordinal()} */
    private static final int[] FACE_SIDE = new int[MAX_FACE_SLOTS];
    /**
     * Premier coin de chaque face, d'où les trois autres sont atteints par les deux axes ci-dessous. Gardée comme
     * le plan de la face, pour pouvoir calculer la lumière sur une grille à l'intérieur et non à ses seuls coins.
     */
    private static final double[] FACE_ORIGIN_X = new double[MAX_FACE_SLOTS],
            FACE_ORIGIN_Y = new double[MAX_FACE_SLOTS], FACE_ORIGIN_Z = new double[MAX_FACE_SLOTS];
    /** Les deux axes de chaque face, de ce coin vers les deux coins suivants */
    private static final float[] FACE_ALONG_X = new float[MAX_FACE_SLOTS], FACE_ALONG_Y = new float[MAX_FACE_SLOTS],
            FACE_ALONG_Z = new float[MAX_FACE_SLOTS], FACE_UP_X = new float[MAX_FACE_SLOTS],
            FACE_UP_Y = new float[MAX_FACE_SLOTS], FACE_UP_Z = new float[MAX_FACE_SLOTS];
    /**
     * Lumière à chacun des quatre coins, qui dit si la face est éclairée et à quel point uniformément. Les faces
     * voisines partagent leurs coins, donc la lumière passe de l'une à l'autre sans couture.
     */
    private static final float[] FACE_CORNER_LIGHT = new float[MAX_FACE_SLOTS * 4];
    /**
     * Et la place de chaque coin dans le faisceau, en coordonnées du dégradé. Calculée à la prise de la face, ce
     * qui écarte aussi les faces que le faisceau n'atteint pas.
     */
    private static final float[] FACE_CORNER_U = new float[MAX_FACE_SLOTS * 4],
            FACE_CORNER_V = new float[MAX_FACE_SLOTS * 4];
    /** Lumière à chaque noeud de la grille de la face en cours de dessin */
    private static final float[] FACE_GRID = new float[(MAX_FACE_STEPS + 1) * (MAX_FACE_STEPS + 1)];
    /** Et la place de chaque noeud dans le faisceau : jusqu'à quatre sous-quads se rejoignent sur un noeud */
    private static final float[] FACE_GRID_U = new float[(MAX_FACE_STEPS + 1) * (MAX_FACE_STEPS + 1)],
            FACE_GRID_V = new float[(MAX_FACE_STEPS + 1) * (MAX_FACE_STEPS + 1)];
    /**
     * Faces qu'une lumière examine, prises ou non : le parcours s'étend aux voisines de chaque face prise, et les
     * deux tables ci-dessous sont dimensionnées là-dessus pour ne jamais se remplir
     */
    private static final int MAX_VISITED_FACES = 768;
    private static int visitedFaces;
    /** Dernière marque distribuée aux tables de clés, qui ne fait que croître, voir nextStamp */
    private static int tableStamp;
    /**
     * Faces max pour la lumière en cours : {@link DynamXLightFx#MAX_LIT_FACES} pour les surfaces d'un faisceau,
     * {@link DynamXLightFx#MAX_GROUND_FACES} pour le sol, {@link DynamXLightFx#MAX_PATCH_FACES} pour une tache
     */
    private static int faceBudget;
    /**
     * Faces déjà examinées, en clés de {@link DynamXLightFx#faceKey} : une face ne l'est qu'une fois, ce qui
     * termine le parcours
     */
    private static final int FACE_KEY_MASK = 2047;
    private static final int[] FACE_KEYS = new int[FACE_KEY_MASK + 1];
    private static final int[] FACE_STAMPS = new int[FACE_KEY_MASK + 1];
    /**
     * Lumière des coins déjà calculés : jusqu'à quatre faces partagent un coin, et en calculer un coûte un rayon.
     * Assez grande pour toute la grille de chaque face, pour ne jamais se remplir.
     */
    private static final int CORNER_KEY_MASK = 16383;
    private static final int[] CORNER_KEYS = new int[CORNER_KEY_MASK + 1];
    private static final int[] CORNER_STAMPS = new int[CORNER_KEY_MASK + 1];
    private static final float[] CORNER_LIGHT = new float[CORNER_KEY_MASK + 1];
    /**
     * Marque des deux tables ci-dessus pour la lumière en cours : ce qu'elles portent sous une autre marque
     * appartient à une lumière déjà dessinée et compte comme vide
     */
    private static int faceStamp;
    /**
     * Bloc autour duquel les clés des deux tables sont construites, celui de la lampe : une position relative tient
     * dans un seul nombre
     */
    private static int keyBaseX, keyBaseY, keyBaseZ;
    /**
     * Centre et rayon de la tache qu'un faisceau pose où il atterrit. Rayon 0 pour un projecteur, dont la lumière
     * décroît avec la place du point dans son faisceau.
     */
    private static double patchX, patchY, patchZ;
    private static float patchRadius;

    /**
     * Vrai pendant la pose de la lumière d'un projecteur sur le dessus des blocs, plutôt que sur les surfaces
     * dressées dans son faisceau. Même code pour les deux : seules changent les faces autorisées et la
     * décroissance, qui au sol suit la place du point dans l'empreinte et non dans le faisceau.
     */
    private static boolean groundMode;
    /** Empreinte en cours : direction horizontale du faisceau, portée, et largeurs à la lampe et au bout */
    private static float groundAlongX, groundAlongZ, groundLength, groundNear, groundFar;
    /**
     * Distance derrière la lampe, le long de l'empreinte, où ses deux bords se rejoignent : toujours négative. Les
     * deux coordonnées du dégradé sont mesurées de là, ce qui les fait varier linéairement sur le sol.
     */
    private static float groundApex;
    /**
     * Et la distance devant la lampe où la lumière commence : la route sous le pare-chocs n'est pas éclairée, et
     * ses blocs ne sont même pas examinés
     */
    private static float groundStart;
    /** Place du dernier point passé à {@link DynamXLightFx#profileCoords}, en coordonnées du dégradé */
    private static float profileU, profileV;
    /** Et les bords du faisceau que ce même point dépasse, voir {@link DynamXLightFx#PROFILE_PAST_HIGH} */
    private static int profileSide;
    /**
     * Hauteur du sol sous la lampe : un terrain de niveau avec elle, sans rien dans le faisceau, est forcément
     * atteint et n'est pas tracé dans le monde
     */
    private static float groundLevel;
    /** Luminosité du soleil cette image, qui atténue toutes ses lumières et qu'aucune ne modifie */
    private static float frameSunBrightness;
    /** Si cette image ne garde que les halos, le soleil étant trop haut pour qu'on distingue le reste */
    private static boolean frameGlowsOnly;
    /** Tronçon d'une ligne de blocs que l'empreinte peut atteindre, voir {@link DynamXLightFx#footprintRow} */
    private static int rowFromZ, rowToZ;
    /** Ce même tronçon pendant son calcul, en blocs depuis la lampe le long de z */
    private static double rowLow, rowHigh;
    /**
     * Surfaces déjà trouvées sous le terrain, adressées par la colonne de blocs et la hauteur de départ. Partagée
     * par toutes les lumières et gardée d'une image à l'autre, car le terrain ne bouge pas : les empreintes d'une
     * zone se recouvrent, et chercher la surface d'une colonne lit cette colonne du monde.
     */
    private static final int GROUND_KEY_MASK = 16383;
    private static final int[] GROUND_KEYS = new int[GROUND_KEY_MASK + 1];
    private static final int[] GROUND_STAMPS = new int[GROUND_KEY_MASK + 1];
    private static final float[] GROUND_HEIGHTS = new float[GROUND_KEY_MASK + 1];
    /**
     * Ce que le terrain fait à la lumière, adressé par le bloc : s'il l'arrête, s'il remplit son bloc, et s'il
     * masque ce qui est derrière. C'est ce qu'un rayon lit dans le monde, et les rayons d'une image traversent les
     * mêmes blocs sans cesse, d'où cette table gardée d'une image à l'autre.
     */
    private static final int BLOCK_KEY_MASK = 32767;
    private static final int[] BLOCK_KEYS = new int[BLOCK_KEY_MASK + 1];
    private static final int[] BLOCK_STAMPS = new int[BLOCK_KEY_MASK + 1];
    private static final byte[] BLOCK_TERRAIN = new byte[BLOCK_KEY_MASK + 1];
    /** Rien n'arrête la lumière : air, ou bloc sans boîte (herbe, fleur, torche, rail, eau, lave) */
    private static final byte TERRAIN_CLEAR = 0;
    /** Arrête la lumière et remplit tout son bloc, ce qui est le cas de l'essentiel du terrain */
    private static final byte TERRAIN_FULL = 1;
    /** Arrête la lumière sur une boîte propre (dalle, marche, barrière) : le seul cas que la table ne résout pas */
    private static final byte TERRAIN_BOX = 2;
    /** Et le bit, en plus de ces trois états, disant que le bloc masque ce qui est derrière lui */
    private static final byte TERRAIN_OPAQUE = 4;
    /** Marque de ces deux tables, incrémentée quand le terrain est supposé avoir changé et non à chaque image */
    private static int groundStamp;
    /**
     * Images pendant lesquelles le terrain gardé est cru. Il ne change que quand quelque chose le change, hors de
     * portée du renderer : les deux tables sont donc simplement jetées à cette fréquence.
     */
    private static final int TERRAIN_GENERATION_FRAMES = 20;
    /** Images dessinées depuis le dernier vidage de ces tables */
    private static int terrainGenerationAge;
    /** Surfaces demandées par cette image, et combien l'étaient déjà par une autre de ses lumières */
    private static int frameGroundLookups, frameGroundHits;
    /**
     * Et combien de projecteurs ont posé leur lumière sur une route plate, soit un seul quad chacun au lieu de
     * quelques centaines de faces
     */
    private static int frameFlatPools, frameBlockPools;
    /**
     * Profondeur de recherche de la surface d'une colonne, fixe quel que soit le demandeur, pour qu'une réponse
     * serve toutes les lumières de l'image
     */
    private static final int GROUND_MAX_DROP = MathHelper.ceil(MAX_BEAM_LENGTH) + GROUND_EXTRA_DROP;
    /**
     * Distance, le long du faisceau, où quelque chose a été trouvé dressé dedans, et donc à partir d'où le terrain
     * vaut d'être tracé dans le monde : c'est la seule chose qui puisse ombrer un sol parfaitement plat.
     * {@link Float#MAX_VALUE} veut dire tout le faisceau.
     */
    private static float groundTracedFrom;

    /**
     * Repère du faisceau du projecteur en cours : sa direction est {@link LightFxEntry#beamX}, et voici l'axe
     * horizontal en travers et l'axe vers le haut
     */
    private static float spotAcrossX, spotAcrossZ, spotUpX, spotUpY, spotUpZ;

    /* Résultat du dernier traceBlocks : la face du bloc qui a arrêté le rayon, et quel bloc c'était. */

    @Nullable
    private static EnumFacing blockFace;
    private static int blockX, blockY, blockZ;
    /** Positions de blocs réutilisées par les tracés et les parcours de faces */
    private static final BlockPos.MutableBlockPos TRACE_POS = new BlockPos.MutableBlockPos(),
            FACE_POS = new BlockPos.MutableBlockPos(), TERRAIN_POS = new BlockPos.MutableBlockPos();

    /**
     * Chunks par lesquels les blocs sont lus : tout ici parcourt de longues suites de blocs voisins, et demander
     * chaque bloc au monde y cherche son chunk à chaque fois. Un seul ne suffit pas, les rayons d'une lampe près
     * d'un coin passant tous dans un voisin.
     */
    private static final int CACHED_CHUNKS = 4;
    /** Les derniers chunks lus, et la case du prochain : un anneau de quatre, parcouru à chaque échec */
    private static final Chunk[] CACHED_CHUNKS_AT = new Chunk[CACHED_CHUNKS];
    private static final int[] CACHED_CHUNK_X = new int[CACHED_CHUNKS], CACHED_CHUNK_Z = new int[CACHED_CHUNKS];
    private static int nextCachedChunk;
    /** Marque une case sans aucun chunk, ce qu'aucune position réelle ne peut valoir */
    private static final int NO_CHUNK = Integer.MIN_VALUE;

    static {
        forgetChunks();
    }

    private static final org.joml.Vector3f TRANSFORM_TEMP = new org.joml.Vector3f();
    /**
     * Vecteur de travail pour passer la longueur de la lampe en espace monde, distinct de
     * {@link DynamXLightFx#TRANSFORM_TEMP}, qui porte en même temps la direction du faisceau
     */
    private static final org.joml.Vector3f SPAN_TEMP = new org.joml.Vector3f();
    /** Position de bloc réutilisée par toutes les recherches de sol */
    private static final BlockPos.MutableBlockPos GROUND_POS = new BlockPos.MutableBlockPos();
    /** Direction du faisceau de la lumière soumise, en espace local, gardée pour mesurer sa lampe */
    private static float localDirX, localDirY, localDirZ;

    /* Résultat du dernier traceLight : où la lumière a atterri, sur quelle face, et si cette face est celle d'une
       entité plutôt que d'un bloc. */

    private static double traceX, traceY, traceZ;
    @Nullable
    private static EnumFacing traceFace;
    private static boolean traceEntity;
    /** Face par laquelle le dernier rayon de {@link DynamXLightFx#traceOccluder} est entré dans la boîte */
    @Nullable
    private static EnumFacing boxFace;
    /** Face d'entrée d'un rayon dans une boîte, par axe et par signe de sa direction */
    private static final EnumFacing[] ENTRY_FACES = {EnumFacing.WEST, EnumFacing.EAST, EnumFacing.DOWN,
            EnumFacing.UP, EnumFacing.NORTH, EnumFacing.SOUTH};

    /** Ce que les lumières de la dernière image ont donné, quand {@link DynamXLightFx#debugCapture} est actif */
    private static final List<String> LAST_FRAME_REPORT = new ArrayList<>();
    /**
     * Les lampes examinées cette image et laissées sans effet, et pourquoi : un packmaker qui cherche pourquoi sa
     * lumière ne brille pas doit voir les lampes écartées, qui n'entrent jamais dans la liste ci-dessus
     */
    private static final List<String> SKIPPED = new ArrayList<>();
    /** Lampes écartées max nommées dans le rapport, pour ne pas chasser du chat les lumières dessinées */
    private static final int MAX_SKIPPED_REPORTED = 24;
    /** Lumières écartées car rien de ce qu'elles dessinent n'est à l'écran, pour le rapport de débogage */
    private static int frameCulled;
    /** Vrai tant que <code>/client_dynamx lightfx debug</code> est actif : le renderer décrit ce qu'il a collecté */
    private static boolean debugCapture;

    /* Drapeaux open gl que les effets changent. Ils sont lus avant tout dessin et remis exactement tels quels
       ensuite : deviner l'état laissé par Minecraft ne marche pas, il diffère selon la vue, et une valeur remise de
       travers survit dans le reste de l'image en vue troisième personne. */

    private static final int[] WATCHED_GL_FLAGS = {GL11.GL_FOG, GL11.GL_LIGHTING, GL11.GL_ALPHA_TEST, GL11.GL_BLEND,
            GL11.GL_CULL_FACE, GL11.GL_DEPTH_TEST, GL11.GL_TEXTURE_2D};
    private static final String[] WATCHED_GL_NAMES = {"fog", "lighting", "alphaTest", "blend", "cull", "depthTest",
            "texture2D", "depthWrite", "lightmapUnit"};
    private static final int FLAG_FOG = 0, FLAG_LIGHTING = 1, FLAG_ALPHA = 2, FLAG_BLEND = 3, FLAG_CULL = 4,
            FLAG_DEPTH = 5, FLAG_TEXTURE = 6, FLAG_DEPTH_WRITE = 7, FLAG_LIGHTMAP = 8;
    private static final boolean[] GL_BEFORE = new boolean[WATCHED_GL_NAMES.length];
    private static final boolean[] GL_AFTER = new boolean[WATCHED_GL_NAMES.length];
    /** Blending et shading tels qu'ils ont été trouvés, qui sont des valeurs et non des drapeaux */
    private static int blendSrcBefore, blendDstBefore, shadeModelBefore;

    public static boolean isEnabled() {
        return DynamXConfig.enableLightFx && DynamXConfig.lightFxIntensity > 0;
    }

    /** Active ou coupe la capture de débogage, voir {@link DynamXLightFx#getLastFrameReport()} */
    public static void setDebugCapture(boolean capture) {
        debugCapture = capture;
        if (!capture) {
            LAST_FRAME_REPORT.clear();
        }
    }

    public static boolean isDebugCapture() {
        return debugCapture;
    }

    /** @return Une ligne par lumière rendue à la dernière image, vide si la capture de débogage est coupée */
    public static List<String> getLastFrameReport() {
        return LAST_FRAME_REPORT;
    }

    /** @return L'identifiant de l'entité chevauchée par le joueur, ou -1 à pied, qu'aucune lumière ne vaut */
    private static int ownVehicleId() {
        if (ClientEventHandler.MC.player == null) {
            return -1;
        }
        // L'entité sur laquelle le joueur est assis, soit le véhicule : un siège est une part de son modèle
        Entity ridden = ClientEventHandler.MC.player.getRidingEntity();
        return ridden == null ? -1 : ridden.getEntityId();
    }

    /**
     * Soumet chaque lampe allumée d'une source de lumière du scene graph, en lisant sa position monde et
     * l'orientation de son faisceau dans les transformations du noeud. Ne fait rien hors du rendu du monde.
     *
     * @param rotationPoint Le point de rotation du noeud, où le transform est posé et par rapport auquel les lampes
     *                      sont placées, ou null quand l'objet est dessiné avec la translation du noeud appliquée :
     *                      ses lampes sont alors déjà dans l'espace du transform
     * @param onVariantName Le nom du matériau allumé, qui sert à deviner la couleur, peut être null
     */
    public static void submitFromScene(IRenderContext context, PartLightSource part, LightObject light,
                                       Matrix4f transform, @Nullable Vector3f rotationPoint,
                                       @Nullable Vector3f nodeScale, @Nullable String onVariantName) {
        if (!isEnabled() || ClientEventHandler.MC.world == null || light.getGlowIntensity() <= 0) {
            return;
        }
        List<LightFxLamp> lamps = part.getLightFxLamps();
        if (lamps.isEmpty()) {
                // Objet 3D illisible : nulle part où placer les effets
            if (debugCapture) {
                rememberSkipped(part, light, 0, 0, null, "objet 3D introuvable ou illisible");
            }
            return;
        }
        // Identifie le porteur, pour écarter les lumières hors du monde et les soumissions en double
        int ownerId;
        // L'entité porteuse, qui ne doit jamais arrêter sa propre lumière : la lampe est dans sa bounding box
        int ownerEntityId = -1;
        if (context instanceof BaseRenderContext.EntityRenderContext && ((BaseRenderContext.EntityRenderContext) context).getEntity() != null) {
            ownerEntityId = ((BaseRenderContext.EntityRenderContext) context).getEntity().getEntityId();
            ownerId = ownerEntityId;
        } else if (context instanceof BaseRenderContext.BlockRenderContext && ((BaseRenderContext.BlockRenderContext) context).getTileEntity() != null) {
            ownerId = ((BaseRenderContext.BlockRenderContext) context).getTileEntity().getPos().hashCode();
        } else {
            return;
        }
        if (DynamXConfig.lightFxOwnVehicleOnly && ownerEntityId != ownVehicleId()) {
            // Mon véhicule uniquement : tout le reste est écarté avant qu'aucune lampe ne soit placée
            return;
        }

        int[] configuredColor = light.getLightColor();
        // La couleur du pack couvre toute la lumière ; sinon chaque lampe, et chaque tronçon d'une lampe
        // multicolore, prend la couleur des pixels dont elle est peinte
        int configuredRgb = configuredColor != null && configuredColor.length >= 3
                ? ((configuredColor[0] & 0xFF) << 16) | ((configuredColor[1] & 0xFF) << 8) | (configuredColor[2] & 0xFF)
                : -1;
        LightColorResolver.LightColors colors = configuredRgb >= 0 ? null
                : LightColorResolver.resolve(context.getModel(), part.getObjectName(), onVariantName, lamps);

        RenderManager renderManager = ClientEventHandler.MC.getRenderManager();
        double maxDistance = DynamXConfig.lightFxMaxDistance + VIEWER_CULL_SLACK;
        int lightKey = (ownerId * 31 + System.identityHashCode(part)) * 31 + System.identityHashCode(light);
        float scaleX = nodeScale == null ? 1 : nodeScale.x, scaleY = nodeScale == null ? 1 : nodeScale.y;
        float scaleZ = nodeScale == null ? 1 : nodeScale.z;
        // Position des lampes sur le modèle, seulement pour le rapport de débogage
        float placedX = rotationPoint != null || part.getPosition() == null ? 0 : part.getPosition().x;
        float placedY = rotationPoint != null || part.getPosition() == null ? 0 : part.getPosition().y;
        float placedZ = rotationPoint != null || part.getPosition() == null ? 0 : part.getPosition().z;
        boolean beaconLight = isBeaconLight(light, lamps);
        for (int index = 0; index < lamps.size(); index++) {
            LightFxLamp lamp = lamps.get(index);
            LightObject.EnumGlowType type = resolveGlowType(light, lamp);
            if (type == LightObject.EnumGlowType.NONE && beaconLight) {
                // Lampe d'une lumière dont les autres lampes sont des gyrophares : les feux de calandre d'une
                // voiture de police font partie du même feu bleu que la rampe de son toit
                type = LightObject.EnumGlowType.BEACON;
            }
            if (type == LightObject.EnumGlowType.NONE) {
                if (debugCapture) {
                    rememberSkipped(part, light, index, lamps.size(), lamp,
                            light.getGlowType() == LightObject.EnumGlowType.AUTO ? "aucun effet, auto"
                                    : "GlowType " + light.getGlowType());
                }
                continue;
            }
            // La couleur de cette lampe, et les couleurs le long d'elle si elle en porte plusieurs
            int color = colors == null || index >= colors.lamps.length ? configuredRgb : colors.lamps[index];
            int[] glowColors = colors == null || index >= colors.stretches.length ? null : colors.stretches[index];
            boolean beacon = type == LightObject.EnumGlowType.BEACON;
            if (DynamXConfig.lightFxBeaconsOnly && !beacon) {
                    // Gyrophares uniquement : un phare est ce qui coûte, un gyrophare ce qui doit être vu
                if (debugCapture) {
                    rememberSkipped(part, light, index, lamps.size(), lamp, "gyrophares uniquement, " + type);
                }
                continue;
            }
            boolean spot = type == LightObject.EnumGlowType.SPOT;
            // Seul un gyrophare qui tourne vraiment balaie : sans rotation dans son transform, le balayage figerait
            // ses lobes et ses faisceaux dans des directions fixes. Et seulement si le joueur le veut.
            boolean sweeping = beacon && light.isRotating() && DynamXConfig.lightFxRotatingBeaconSweep;
            // Seul un gyrophare balayant émet des faisceaux : un projecteur éclaire sans dessiner son volume d'air
            int beamCount = sweeping ? light.getGlowBeamCount() : 0;
            if (!resolveDirection(light, type, lamp, transform)) {
                continue;
            }
            float dirX = TRANSFORM_TEMP.x, dirY = TRANSFORM_TEMP.y, dirZ = TRANSFORM_TEMP.z;
            // Le faisceau part de la base de la lampe, sa face arrière le long de sa direction : parti de la face
            // avant, il commencerait en l'air, avec un vide entre le véhicule et sa propre lumière
            float emitterOffset = spot ? -lampExtentAlongBeam(lamp.getHalfSize()) : 0;
            int lampKey = lightKey * 31 + index;
            if (!markSubmitted(lampKey)) {
                continue;
            }
            // Les transformations du scene graph sont relatives à l'entité de vue, qui n'est pas la caméra : la
            // distance mesurée ici ne sert qu'au filtre de budget, render les remesure depuis la caméra
            Vector3f lampPosition = lamp.getPosition();
            transform.transformPosition(TRANSFORM_TEMP.set(
                    (lampPosition.x - (rotationPoint == null ? 0 : rotationPoint.x)) / scaleX,
                    (lampPosition.y - (rotationPoint == null ? 0 : rotationPoint.y)) / scaleY,
                    (lampPosition.z - (rotationPoint == null ? 0 : rotationPoint.z)) / scaleZ));
            double viewerDistanceSq = TRANSFORM_TEMP.lengthSquared();
                // Marge pour l'écart entre la caméra et l'entité de vue en vue troisième personne
            if (viewerDistanceSq > maxDistance * maxDistance) {
                continue;
            }
            double x = renderManager.viewerPosX + TRANSFORM_TEMP.x;
            double y = renderManager.viewerPosY + TRANSFORM_TEMP.y;
            double z = renderManager.viewerPosZ + TRANSFORM_TEMP.z;
            // Une lampe est rarement un point : son halo est étalé sur son axe le plus long, celui où ses couleurs
            // ont été lues, pour que toute la lampe brille dans les bonnes couleurs
            Vector3f halfSize = lamp.getHalfSize();
            float lampHalfWidth;
            if (lamp.getLongestAxis() == 0) {
                SPAN_TEMP.set(halfSize.x / scaleX, 0, 0);
                lampHalfWidth = Math.max(halfSize.y, halfSize.z);
            } else if (lamp.getLongestAxis() == 1) {
                SPAN_TEMP.set(0, halfSize.y / scaleY, 0);
                lampHalfWidth = Math.max(halfSize.x, halfSize.z);
            } else {
                SPAN_TEMP.set(0, 0, halfSize.z / scaleZ);
                lampHalfWidth = Math.max(halfSize.x, halfSize.y);
            }
            lampHalfWidth = Math.max(lampHalfWidth, SPOT_MIN_LAMP_HALF_WIDTH);
            // Le transform applique l'échelle du noeud, divisée ci-dessus, pour obtenir des unités monde
            transform.transformDirection(SPAN_TEMP);

            int before = count;
            submit(x, y, z, color, light.getGlowRadius(), light.getGroundLightRadius(type),
                    light.getGlowIntensity(), beamCount, beacon, sweeping, spot, dirX, dirY, dirZ,
                    lampHalfWidth, emitterOffset, ownerEntityId, viewerDistanceSq);
            if (count > before) {
                LightFxEntry submitted = ENTRIES.get(count - 1);
                submitted.glowSpanX = SPAN_TEMP.x;
                submitted.glowSpanY = SPAN_TEMP.y;
                submitted.glowSpanZ = SPAN_TEMP.z;
                submitted.glowColors = glowColors;
                // Quelle lampe de quelle lumière de quel véhicule, ce qui départage les lumières à égale distance
                submitted.stableKey = lampKey;
            }
            if (debugCapture && count > before) {
                LightObject.EnumGlowType configured = light.getGlowType();
                // Dit comment le type d'effets a été décidé, pour pouvoir expliquer une lumière sans effet
                ENTRIES.get(count - 1).debugName = part.getPartName() + "/" + light.getLightIdString()
                        + (lamps.size() > 1 ? " lampe " + (index + 1) + "/" + lamps.size() : "") + " " + type
                        + " (" + (configured == LightObject.EnumGlowType.AUTO
                        ? resolveGlowType(light, lamp) == type ? "auto" : "auto, gyrophare du même feu"
                        : "GlowType " + configured)
                        + (light.isRotating() ? ", en rotation" : light.isFlashing() ? ", clignotant"
                        : light.isBlinking() ? ", toujours allumé" : ", fixe")
                        + (light.getGlowDirection() != null ? ", orienté" : "")
                        + String.format(", lampe de %.2fx%.2fx%.2fm, à %.0f%% de la hauteur du modèle, %+.0f%% le long",
                        lamp.getHalfSize().x * 2, lamp.getHalfSize().y * 2, lamp.getHalfSize().z * 2,
                        lamp.getRelativeHeight() * 100, lamp.getRelativeForward() * 100)
                        + ")"
                        + String.format(" couleur #%06X", color) + describeGlowColors(glowColors)
                        + String.format(" pos=%.2f %.2f %.2f", lampPosition.x + placedX, lampPosition.y + placedY,
                        lampPosition.z + placedZ);
            }
        }
    }

    /**
     * Note une lampe examinée et laissée sans effet, pour le rapport de débogage. Nommée avec son emplacement sur
     * le modèle, sur lequel repose la déduction : c'est là qu'un packmaker trouve pourquoi sa lumière ne brille pas.
     */
    private static void rememberSkipped(PartLightSource part, LightObject light, int index, int lampCount,
                                        @Nullable LightFxLamp lamp, String why) {
        if (SKIPPED.size() >= MAX_SKIPPED_REPORTED) {
            return;
        }
        String described = part.getPartName() + "/" + light.getLightIdString()
                + (lampCount > 1 ? " lampe " + (index + 1) + "/" + lampCount : "")
                + " SANS EFFET (" + why
                + (light.isRotating() ? ", en rotation" : light.isFlashing() ? ", clignotant"
                : light.isBlinking() ? ", toujours allumé" : ", fixe")
                + (lamp == null ? "" : String.format(", lampe de %.2fx%.2fx%.2fm, à %.0f%% de la hauteur du"
                + " modèle, %+.0f%% le long", lamp.getHalfSize().x * 2, lamp.getHalfSize().y * 2,
                lamp.getHalfSize().z * 2, lamp.getRelativeHeight() * 100, lamp.getRelativeForward() * 100))
                + ")";
        // Tous les véhicules d'un même modèle écartent les mêmes lampes, pour la même raison
        if (!SKIPPED.contains(described)) {
            SKIPPED.add(described);
        }
    }

    /** Ajoute les lampes sans effet au rapport de débogage, après les lumières dessinées */
    private static void appendSkippedToReport() {
        if (SKIPPED.isEmpty()) {
            return;
        }
        LAST_FRAME_REPORT.add(SKIPPED.size() + " lampe(s) sans effet de lumière"
                + (SKIPPED.size() >= MAX_SKIPPED_REPORTED ? " (et d'autres, non listées)" : "") + " :");
        LAST_FRAME_REPORT.addAll(SKIPPED);
    }

    /** Dit dans quelles couleurs le halo d'une lampe multicolore est dessiné, pour le rapport de débogage */
    private static String describeGlowColors(@Nullable int[] glowColors) {
        if (glowColors == null) {
            return "";
        }
        StringBuilder described = new StringBuilder(", halos");
        for (int stretch = 0; stretch < glowColors.length; stretch++) {
            // Une par suite d'une même couleur, soit une par halo réellement dessiné
            if (stretch == 0 || !LightColorResolver.sameColor(glowColors[stretch], glowColors[stretch - 1])) {
                described.append(String.format(" #%06X", glowColors[stretch]));
            }
        }
        return described.toString();
    }

    /**
     * Dit si une lumière est un feu d'avertissement d'après ses lampes : il suffit que l'une d'elles soit montée et
     * clignote comme un gyrophare. Les lampes d'un objet de lumière sont un seul circuit, et les feux de calandre
     * d'une voiture de police sont des gyrophares autant que la rampe de son toit, alors que seuls ils sont là où se
     * trouve un clignotant, ce pour quoi {@link DynamXLightFx#resolveGlowType} ne peut pas les en distinguer.
     */
    private static boolean isBeaconLight(LightObject light, List<LightFxLamp> lamps) {
        if (light.getGlowType() != LightObject.EnumGlowType.AUTO || lamps.size() < 2) {
            // Un pack qui a dit ce que projette sa lumière est cru, et une lampe seule n'a rien pour la renseigner
            return false;
        }
        for (int index = 0; index < lamps.size(); index++) {
            if (resolveGlowType(light, lamps.get(index)) == LightObject.EnumGlowType.BEACON) {
                return true;
            }
        }
        return false;
    }

    /**
     * Résout le type d'effets d'une lampe, deviné d'après la lumière et son emplacement quand il vaut
     * {@link LightObject.EnumGlowType#AUTO} : au-dessus du milieu du véhicule, haut et clignotante, un gyrophare ;
     * tout à l'avant, basse et fixe, un projecteur ; tout le reste n'a aucun effet propre, un feu arrière ou un
     * clignotant n'étant qu'une texture allumée sur le modèle. <br>
     * C'est la position en longueur qui porte la déduction et non la hauteur, les feux arrière étant souvent aussi
     * hauts qu'une rampe de toit. Une lampe sans effet ici peut encore en recevoir un par la compagnie qu'elle
     * tient, voir {@link DynamXLightFx#isBeaconLight}.
     *
     * @return Le type d'effets, jamais {@link LightObject.EnumGlowType#AUTO}
     */
    private static LightObject.EnumGlowType resolveGlowType(LightObject light, LightFxLamp lamp) {
        LightObject.EnumGlowType type = light.getGlowType();
        if (type != LightObject.EnumGlowType.AUTO) {
            return type;
        }
        // Une lampe qui tourne sur elle-même est un gyrophare, où qu'elle soit montée
        if (light.isRotating()) {
            return LightObject.EnumGlowType.BEACON;
        }
        // Une lampe explicitement orientée est un projecteur, où qu'elle soit montée
        if (light.getGlowDirection() != null) {
            return LightObject.EnumGlowType.SPOT;
        }
        float relativeForward = lamp.getRelativeForward();
        float relativeHeight = lamp.getRelativeHeight();
        if (!Float.isNaN(relativeForward) && !Float.isNaN(relativeHeight)) {
            // Au-dessus du milieu du véhicule et pas sous sa carrosserie : une rampe de toit. Seule une
            // clignotante est un gyrophare ; une fixe est une lampe de position, donc une texture allumée.
            if (Math.abs(relativeForward) <= BEACON_MAX_RELATIVE_OFFSET
                    && relativeHeight >= BEACON_MIN_RELATIVE_HEIGHT) {
                return light.isFlashing() ? LightObject.EnumGlowType.BEACON : LightObject.EnumGlowType.NONE;
            }
            // Tout à l'avant du véhicule et fixe : un phare, la seule lampe de carrosserie qui éclaire vraiment
            return relativeForward >= AUTO_SPOT_MIN_FORWARD && !light.isFlashing()
                    ? LightObject.EnumGlowType.SPOT : LightObject.EnumGlowType.NONE;
        }
        // Modèle non mesurable : on retombe sur la position brute de la lampe, qui ne dit où est l'avant que si le
        // modèle est construit autour de son milieu
        return lamp.getPosition().z > AUTO_SPOT_MIN_Z && !light.isFlashing()
                ? LightObject.EnumGlowType.SPOT : LightObject.EnumGlowType.NONE;
    }

    /**
     * Mesure la profondeur d'une lampe le long de son faisceau : la lampe est une boîte dans l'espace du modèle, et
     * la portée de cette boîte dans la direction du faisceau est la somme de ses demi-dimensions pondérées par elle
     *
     * @return Cette distance, en blocs, jamais négative
     */
    private static float lampExtentAlongBeam(Vector3f halfSize) {
        return Math.min(Math.abs(localDirX * halfSize.x) + Math.abs(localDirY * halfSize.y)
                + Math.abs(localDirZ * halfSize.z), MAX_EMITTER_OFFSET);
    }

    /**
     * Calcule la direction monde du faisceau d'une lampe dans {@link DynamXLightFx#TRANSFORM_TEMP}. Un projecteur
     * vise la direction configurée, ou par défaut l'avant du modèle (son arrière si la lampe est derrière son
     * centre) incliné vers le bas ; les faisceaux d'un gyrophare étant répartis autour de son axe vertical,
     * n'importe quel axe horizontal fait le premier.
     *
     * @return Vrai si une direction a pu être calculée
     */
    private static boolean resolveDirection(LightObject light, LightObject.EnumGlowType type, LightFxLamp lamp,
                                            Matrix4f transform) {
        if (type == LightObject.EnumGlowType.SPOT) {
            Vector3f configured = light.getGlowDirection();
            if (configured != null) {
                TRANSFORM_TEMP.set(configured.x, configured.y, configured.z);
            } else {
                // L'avant d'un modèle DynamX est son axe +z, voir DynamXGeometry#FORWARD_DIRECTION. Le bout du
                // véhicule où est la lampe est mesuré depuis le milieu du modèle, dont l'origine peut être partout.
                float relativeForward = lamp.getRelativeForward();
                float towards = Float.isNaN(relativeForward)
                        ? (lamp.getPosition().z < 0 ? -1 : 1)
                        : (relativeForward < 0 ? -1 : 1);
                TRANSFORM_TEMP.set(0, -SPOT_TILT, towards);
            }
        } else {
            TRANSFORM_TEMP.set(1, 0, 0);
        }
        if (TRANSFORM_TEMP.lengthSquared() < 1e-6f) {
            return false;
        }
        // Gardée pour mesurer la lampe le long de son propre faisceau, boîte donnée dans ce même espace local
        TRANSFORM_TEMP.normalize();
        localDirX = TRANSFORM_TEMP.x;
        localDirY = TRANSFORM_TEMP.y;
        localDirZ = TRANSFORM_TEMP.z;
        transform.transformDirection(TRANSFORM_TEMP);
        if (TRANSFORM_TEMP.lengthSquared() < 1e-6f) {
            return false;
        }
        TRANSFORM_TEMP.normalize();
        return true;
    }

    /**
     * Soumet une lumière allumée, à dessiner en fin d'image
     *
     * @param emitterOffset Le recul du point d'émission derrière le milieu de la lampe, 0 sans faisceau
     * @param ownerEntityId L'identifiant de l'entité porteuse, qui n'arrête jamais la lumière, -1 pour un bloc
     */
    public static void submit(double x, double y, double z, int color, float radius, float groundRadius,
                              float intensity, int beamCount, boolean beacon, boolean sweeping, boolean spot,
                              float beamX, float beamY, float beamZ, float lampHalfWidth, float emitterOffset,
                              int ownerEntityId, double cameraDistanceSq) {
        if (radius <= 0 || intensity <= 0 || count >= HARD_LIGHT_LIMIT) {
            return;
        }
        if (count == ENTRIES.size()) {
            ENTRIES.add(new LightFxEntry());
        }
        LightFxEntry entry = ENTRIES.get(count++);
        entry.set(x, y, z, color, radius, groundRadius, intensity, beamCount, beacon, sweeping, spot,
                beamX, beamY, beamZ);
        entry.emitX = x + beamX * emitterOffset;
        entry.emitY = y + beamY * emitterOffset;
        entry.emitZ = z + beamZ * emitterOffset;
        entry.ownerEntityId = ownerEntityId;
        entry.lampHalfWidth = lampHalfWidth;
        entry.cameraDistanceSq = cameraDistanceSq;
    }

    /** Jette les lumières soumises pendant cette image, sans les dessiner */
    public static void clear() {
        count = 0;
        SKIPPED.clear();
        submittedCount = 0;
        submittedStamp = nextStamp();
        frameOccluderCount = 0;
        forgetChunks();
    }

    /** Oublie les chunks par lesquels les blocs étaient lus : ils peuvent avoir disparu à l'image suivante */
    private static void forgetChunks() {
        for (int slot = 0; slot < CACHED_CHUNKS; slot++) {
            CACHED_CHUNKS_AT[slot] = null;
            CACHED_CHUNK_X[slot] = NO_CHUNK;
            CACHED_CHUNK_Z[slot] = NO_CHUNK;
        }
        nextCachedChunk = 0;
    }

    /**
     * Note une lampe soumise cette image
     *
     * @return Vrai la première fois, faux si elle l'était déjà : certaines configurations (shaders Optifine, mods
     * ajoutant des passes de rendu) rendent la même entité deux fois par image
     */
    private static boolean markSubmitted(int key) {
        if (submittedCount > SUBMITTED_MASK / 2) {
            // Plus de lampes que la table n'en tient : écarter les doublons vaut moins qu'une table pleine
            return true;
        }
        int slot = keySlot(SUBMITTED_KEYS, SUBMITTED_STAMPS, SUBMITTED_MASK, submittedStamp, key);
        if (SUBMITTED_STAMPS[slot] == submittedStamp) {
            return false;
        }
        SUBMITTED_STAMPS[slot] = submittedStamp;
        SUBMITTED_KEYS[slot] = key;
        submittedCount++;
        return true;
    }

    /**
     * Dessine les effets de toutes les lumières soumises pendant cette image, puis les jette. À appeler à la toute
     * fin du rendu du monde, une fois le depth buffer complet, pour que le monde les masque correctement.
     */
    public static void render(float partialTicks) {
        World world = ClientEventHandler.MC.world;
        if (count == 0 || world == null) {
            if (debugCapture) {
                // Rien n'a été dessiné : les lampes écartées sont toute la réponse pour un packmaker
                LAST_FRAME_REPORT.clear();
                LAST_FRAME_REPORT.add("0 lumière(s) collectée(s)"
                        + (DynamXConfig.lightFxOwnVehicleOnly ? ", mon véhicule uniquement" : ""));
                appendSkippedToReport();
            }
            clear();
            return;
        }
        try {
            // Le point de vue d'abord : les lumières ont été collectées par rapport à l'entité de vue
            readCamera();
            computeDetailDistances();
            // Maintenant que la caméra est connue, on remesure les lumières depuis elle et on écarte celles dont
            // rien n'est à l'écran, avant que le budget ne soit distribué à des lampes invisibles
            int culled = 0, visible = 0;
            for (int i = 0; i < count; i++) {
                LightFxEntry entry = ENTRIES.get(i);
                double dx = entry.x - cameraX, dy = entry.y - cameraY, dz = entry.z - cameraZ;
                entry.cameraDistanceSq = dx * dx + dy * dy + dz * dz;
                entry.resolvedBeams = 0;
                entry.projectionLength = 0;
                entry.projectionFade = 1;
                entry.occluderCount = 0;
                entry.projectionBlockedFrom = Float.MAX_VALUE;
                if (!lightInFrustum(entry)) {
                    // Écartées avant le moindre rayon : dans une zone animée c'est l'essentiel des lampes à portée
                    culled++;
                    continue;
                }
                // Les lumières sont recyclées : celles qui restent sont rassemblées en tête de liste
                if (visible != i) {
                    Collections.swap(ENTRIES, visible, i);
                }
                visible++;
            }
            frameCulled = culled;
            if (visible == 0) {
                clear();
                return;
            }
            // Les plus proches d'abord, ordre dans lequel tous les budgets ci-dessous sont distribués
            ENTRIES.subList(0, visible).sort(BY_CAMERA_DISTANCE);
            int budget = Math.max(1, DynamXConfig.lightFxMaxLights);
            int rendered = Math.min(visible, budget);
            // Les dernières lumières du budget sont atténuées plutôt que coupées net, sinon elles apparaissent et
            // disparaissent au gré de l'ordre, qui change avec la caméra
            int fadeFrom = budget >= 8 ? budget - budget / 4 : budget;
            // Soleil au plus haut : rien de ce qu'une lumière pose ne se distingue, seuls les halos restent
            frameGlowsOnly = glowsOnly(world);
            boolean withProjections = !frameGlowsOnly;
            // Partagés entre les lumières de l'image, les plus proches d'abord, plutôt que donnés à chacune
            frameFaceSampleBudget = MAX_FRAME_FACE_SAMPLES;
            frameFaceSamplesLeft = frameFaceSampleBudget;
            frameSeedRaysLeft = MAX_FRAME_SEED_RAYS;
            frameSurfaceFacesLeft = MAX_FRAME_SURFACE_FACES;
            frameGroundColumnsLeft = MAX_FRAME_GROUND_COLUMNS;
            // Ce que le renderer sait du terrain est gardé d'une image à l'autre, et jeté à cette fréquence
            if (groundStamp == 0 || ++terrainGenerationAge >= TERRAIN_GENERATION_FRAMES) {
                invalidateTerrain();
            }
            frameGroundLookups = 0;
            frameGroundHits = 0;
            frameFlatPools = 0;
            frameBlockPools = 0;
            int projectedSpots = 0;
            // La luminosité de chaque lumière, fixée avant tout calcul : seules celles qui se verront en paient
            frameSunBrightness = world.getSunBrightness(partialTicks);
            for (int i = 0; i < rendered; i++) {
                LightFxEntry entry = ENTRIES.get(i);
                entry.brightness = computeBrightness(world, entry);
                if (i >= fadeFrom) {
                    entry.brightness *= 1 - (float) (i - fadeFrom + 1) / (budget - fadeFrom);
                }
            }
            // Les véhicules, joueurs et mobs dans les lumières, cherchés une fois pour toute l'image et partagés :
            // demandés par lumière, cela reparcourt les chunks et les listes d'entités de toute la portée de chaque
            // lampe, soit six fois la même réponse sur un véhicule qui en porte six
            AxisAlignedBB occluderBounds = withProjections ? occluderBounds(rendered) : null;
            frameOccluderCount = 0;
            if (occluderBounds != null) {
                collectFrameOccluders(world, world.getEntitiesWithinAABBExcludingEntity(null, occluderBounds));
            }
            for (int i = 0; i < rendered; i++) {
                LightFxEntry entry = ENTRIES.get(i);
                if (entry.brightness <= MIN_VISIBLE || !withProjections) {
                    continue;
                }
                if (entry.spot) {
                    // Les projections sont la partie coûteuse : seuls les projecteurs les plus proches en ont une
                    if (projectedSpots >= MAX_PROJECTED_SPOTS) {
                        continue;
                    }
                    // Et donc les entités ne sont cherchées qu'ici : sans projection, rien n'est projeté sur elles
                    if (entry.groundRadius > 0) {
                        collectOccluders(entry);
                    }
                    resolveProjection(entry);
                    // Les derniers du budget sont atténués plutôt que coupés net
                    if (projectedSpots >= PROJECTION_FADE_FROM) {
                        entry.projectionFade = 1 - (float) (projectedSpots - PROJECTION_FADE_FROM + 1)
                                / (MAX_PROJECTED_SPOTS - PROJECTION_FADE_FROM);
                    }
                    projectedSpots++;
                    // Un projecteur n'émet aucun faisceau : tout ce qu'il montre est la lumière qu'il pose
                } else if (entry.beamCount > 0) {
                    collectOccluders(entry);
                    resolveBeams(world, entry);
                }
                // Une lampe qui ne fait que briller ne projette rien sur les entités autour d'elle
            }
            // Lit dans GL_BEFORE l'état qui va être changé, que restoreRender remettra
            setupRender();
            try {
                BufferBuilder buffer = Tessellator.getInstance().getBuffer();

                // Première passe : la lumière posée sur les routes et les murs, en deux dégradés différents
                beamPatchesLeft = MAX_BEAM_PATCHES;
                if (withProjections) {
                    // Les surfaces d'abord : leurs rayons disent aussi au sol s'il doit être tracé dans le monde
                    GlStateManager.bindTexture(LightFxTextures.getProfileTextureId());
                    buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
                    for (int i = 0; i < rendered; i++) {
                        LightFxEntry entry = ENTRIES.get(i);
                        if (entry.spot && entry.brightness > MIN_VISIBLE) {
                            renderSpotSurfaces(buffer, world, entry);
                        }
                    }
                    Tessellator.getInstance().draw();

                    // Puis la lumière au sol, dont le dégradé décroît le long du faisceau et non en hauteur
                    GlStateManager.bindTexture(LightFxTextures.getGroundProfileTextureId());
                    buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
                    for (int i = 0; i < rendered; i++) {
                        LightFxEntry entry = ENTRIES.get(i);
                        if (entry.spot && entry.brightness > MIN_VISIBLE) {
                            renderSpotGround(buffer, world, entry);
                        }
                    }
                    Tessellator.getInstance().draw();

                    // Et les taches des faisceaux. Le dégradé rond reste lié pour les faisceaux dans l'air.
                    GlStateManager.bindTexture(LightFxTextures.getSpotTextureId());
                    buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
                    for (int i = 0; i < rendered; i++) {
                        LightFxEntry entry = ENTRIES.get(i);
                        if (!entry.spot && entry.brightness > MIN_VISIBLE) {
                            renderBeamSpots(buffer, world, entry);
                        }
                    }
                    Tessellator.getInstance().draw();
                }

                // Deuxième passe : la lumière qui voyage dans l'air
                if (withProjections) {
                    buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
                    for (int i = 0; i < rendered; i++) {
                        LightFxEntry entry = ENTRIES.get(i);
                        if (entry.brightness > MIN_VISIBLE) {
                            renderBeamCones(buffer, entry);
                        }
                    }
                    Tessellator.getInstance().draw();
                }

                // Troisième passe : les halos des lampes, billboards dessinés avec l'autre dégradé
                GlStateManager.bindTexture(LightFxTextures.getFlareTextureId());
                buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
                for (int i = 0; i < rendered; i++) {
                    LightFxEntry entry = ENTRIES.get(i);
                    if (entry.brightness > MIN_VISIBLE) {
                        renderFlare(buffer, entry);
                    }
                }
                Tessellator.getInstance().draw();

                if (debugCapture) {
                    renderLampMarkers(rendered);
                }
            } finally {
                // L'état open gl doit être rendu tel qu'il a été trouvé, sinon tout le jeu serait mal rendu
                restoreRender();
            }
            if (debugCapture) {
                // Écrit après le dessin : une partie de ce qu'il dit n'y est calculée que là
                captureDebugReport(rendered);
                snapshotGlState(GL_AFTER);
                reportGlLeaks();
            }
        } catch (Exception e) {
            DynamXMain.log.error("Failed to render the DynamX light effects, disabling them until the game restarts", e);
            DynamXConfig.enableLightFx = false;
            abortDrawing();
        } finally {
            clear();
        }
    }

    /**
     * Décrit ce que le renderer a fait des lumières de cette image, pour qu'un packmaker voie pourquoi une lumière
     * ne ressemble pas à ce qu'il attend
     */
    private static void captureDebugReport(int rendered) {
        LAST_FRAME_REPORT.clear();
        LAST_FRAME_REPORT.add(count + " lumière(s) collectée(s), " + rendered + " gardée(s), " + frameCulled
                + " hors de l'écran"
                + (frameGlowsOnly ? ", soleil haut : lueurs uniquement" : "")
                + (DynamXConfig.lightFxOwnVehicleOnly ? ", mon véhicule uniquement" : ""));
        LAST_FRAME_REPORT.add(String.format("détail gardé jusqu'à %.0f bloc(s) par cran de subdivision, "
                        + "%.0f bloc(s) pour l'éventail de rayons, %.0f bloc(s) pour les faisceaux tracés",
                1 / frameFaceStepFactor, Math.sqrt(frameSpotFineDistanceSq),
                Math.sqrt(frameBeamTraceDistanceSq)));
        LAST_FRAME_REPORT.add(String.format("budgets restants de l'image : %d/%d point(s) de lumière, %d/%d "
                        + "rayon(s) cherchant les surfaces, %d/%d face(s) de surface, %d/%d colonne(s) de sol, "
                        + "%d entité(s) qui arrêtent la lumière",
                frameFaceSamplesLeft, frameFaceSampleBudget, frameSeedRaysLeft, MAX_FRAME_SEED_RAYS,
                frameSurfaceFacesLeft, MAX_FRAME_SURFACE_FACES, frameGroundColumnsLeft,
                MAX_FRAME_GROUND_COLUMNS, frameOccluderCount));
        LAST_FRAME_REPORT.add(String.format("sol : %d projection(s) sur route plate (un seul quad), %d bloc par "
                        + "bloc ; %d recherche(s) de surface, dont %d (%.0f%%) déjà connue(s)",
                frameFlatPools, frameBlockPools, frameGroundLookups, frameGroundHits,
                frameGroundLookups == 0 ? 0 : 100f * frameGroundHits / frameGroundLookups));
        LAST_FRAME_REPORT.add(String.format("caméra %.1f %.1f %.1f, rendu centré sur %.1f %.1f %.1f",
                cameraX, cameraY, cameraZ, originX, originY, originZ));
        for (int i = 0; i < rendered; i++) {
            LightFxEntry entry = ENTRIES.get(i);
            LAST_FRAME_REPORT.add(String.format("%s | %.1fm | luminosité %.2f | faisceaux %d | %s | %d entité(s) sur le passage",
                    entry.debugName == null ? "?" : entry.debugName, Math.sqrt(entry.cameraDistanceSq),
                    entry.brightness, entry.resolvedBeams, entry.spot
                            ? String.format("portée %.1fm, %.1fm de large au bout%s%s",
                            entry.projectionLength, entry.projectionWidthFar * 2,
                            entry.projectionBlockedFrom == Float.MAX_VALUE ? ""
                                    : String.format(", bloqué à %.1fm devant, ombres tracées au-delà",
                                    entry.projectionBlockedFrom),
                            entry.projectionFade < 1
                                    ? String.format(", projection atténuée à %.0f%% par le budget",
                                    entry.projectionFade * 100) : "")
                            : String.format("%d faisceau(x) de lumière", entry.beamCount),
                    entry.occluderCount));
        }
        appendSkippedToReport();
    }

    /** Lit les drapeaux open gl que les effets touchent dans le tableau donné */
    private static void snapshotGlState(boolean[] into) {
        for (int i = 0; i < WATCHED_GL_FLAGS.length; i++) {
            into[i] = GL11.glIsEnabled(WATCHED_GL_FLAGS[i]);
        }
        into[WATCHED_GL_FLAGS.length] = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        // Le lightmap vit sur son propre texture unit, qu'il faut activer pour l'interroger
        GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
        into[WATCHED_GL_FLAGS.length + 1] = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
    }

    /**
     * Signale les drapeaux que les effets n'ont pas rendus tels quels : tout ce qui est listé ici survit dans le
     * reste de l'image et est un bug de rendu des effets
     */
    private static void reportGlLeaks() {
        StringBuilder leaked = new StringBuilder();
        for (int i = 0; i < WATCHED_GL_NAMES.length; i++) {
            if (GL_BEFORE[i] != GL_AFTER[i]) {
                leaked.append(leaked.length() == 0 ? "" : ", ").append(WATCHED_GL_NAMES[i])
                        .append(' ').append(GL_BEFORE[i]).append("->").append(GL_AFTER[i]);
            }
        }
        LAST_FRAME_REPORT.add(leaked.length() == 0
                ? "gl rendu intact (brouillard=" + GL_BEFORE[0] + " éclairage=" + GL_BEFORE[1] + ")"
                : "FUITE GL : " + leaked);
    }

    /** Sort le tessellator de son état "en cours de dessin" après un échec, pour que le jeu puisse le réutiliser */
    private static void abortDrawing() {
        try {
            Tessellator.getInstance().getBuffer().finishDrawing();
        } catch (Exception ignored) {
            // Le tessellator ne dessinait pas, rien à faire
        }
    }

    /* Ce qui arrête la lumière : les blocs du monde, et les entités qui s'y trouvent */

    /**
     * Mesure la boîte sur laquelle les entités susceptibles d'arrêter une lumière sont cherchées, soit aussi loin
     * que porte la lumière de la plus lointaine
     */
    @Nullable
    private static AxisAlignedBB occluderBounds(int rendered) {
        double minX = 0, minY = 0, minZ = 0, maxX = 0, maxY = 0, maxZ = 0;
        boolean any = false;
        // Compté exactement comme la boucle qui les demandera, pour que la boîte ne couvre que les lumières qui
        // cherchent vraiment les entités : c'est là tout le coût de la recherche dans une zone animée
        int spots = 0;
        for (int i = 0; i < rendered; i++) {
            LightFxEntry entry = ENTRIES.get(i);
            if (entry.brightness <= MIN_VISIBLE) {
                continue;
            }
            float reach;
            if (entry.spot) {
                if (spots >= MAX_PROJECTED_SPOTS) {
                    continue;
                }
                spots++;
                reach = entry.groundRadius > 0 ? occluderReach(entry) : 0;
            } else {
                reach = entry.beamCount > 0 ? occluderReach(entry) : 0;
            }
            if (reach < 1) {
                // Une lampe qui ne fait que briller, et ne projette donc rien sur rien
                continue;
            }
            if (!any) {
                minX = entry.emitX - reach;
                minY = entry.emitY - reach;
                minZ = entry.emitZ - reach;
                maxX = entry.emitX + reach;
                maxY = entry.emitY + reach;
                maxZ = entry.emitZ + reach;
                any = true;
                continue;
            }
            minX = Math.min(minX, entry.emitX - reach);
            minY = Math.min(minY, entry.emitY - reach);
            minZ = Math.min(minZ, entry.emitZ - reach);
            maxX = Math.max(maxX, entry.emitX + reach);
            maxY = Math.max(maxY, entry.emitY + reach);
            maxZ = Math.max(maxZ, entry.emitZ + reach);
        }
        return any ? new AxisAlignedBB(minX, minY, minZ, maxX, maxY, maxZ) : null;
    }

    /**
     * @return La distance, en blocs, à laquelle la lumière peut rencontrer quelque chose, 0 pour une lampe qui ne
     * fait que briller
     */
    private static float occluderReach(LightFxEntry entry) {
        return entry.spot
                ? Math.min(entry.radius * SPOT_THROW, MAX_BEAM_LENGTH)
                : entry.beamCount > 0 ? Math.min(entry.radius * 1.5f, MAX_BEAM_LENGTH) : 0;
    }

    /**
     * Garde, une fois pour toute l'image, les boîtes des entités susceptibles d'arrêter une lumière : prises
     * ensuite dans cette liste, elles ne coûtent plus que de l'arithmétique
     */
    private static void collectFrameOccluders(World world, List<Entity> entities) {
        frameOccluderCount = 0;
        for (int i = 0; i < entities.size() && frameOccluderCount < MAX_FRAME_OCCLUDERS; i++) {
            Entity entity = entities.get(i);
            // Seul ce dans quoi un joueur se cognerait arrête la lumière : pas un objet au sol ni une flèche
            if (entity.isDead || entity.isInvisible() || !entity.canBeCollidedWith()) {
                continue;
            }
            AxisAlignedBB box = entity.getEntityBoundingBox();
            if (box.maxX - box.minX < MIN_OCCLUDER_SIZE && box.maxY - box.minY < MIN_OCCLUDER_SIZE
                    && box.maxZ - box.minZ < MIN_OCCLUDER_SIZE) {
                continue;
            }
            Entity ridden = entity.getRidingEntity();
            int at = frameOccluderCount * 6;
            FRAME_OCCLUDERS[at] = box.minX;
            FRAME_OCCLUDERS[at + 1] = box.minY;
            FRAME_OCCLUDERS[at + 2] = box.minZ;
            FRAME_OCCLUDERS[at + 3] = box.maxX;
            FRAME_OCCLUDERS[at + 4] = box.maxY;
            FRAME_OCCLUDERS[at + 5] = box.maxZ;
            FRAME_OCCLUDER_IDS[frameOccluderCount] = entity.getEntityId();
            // Une lumière ne s'arrête jamais sur le véhicule qui la porte ni sur qui le chevauche
            FRAME_OCCLUDER_RIDING[frameOccluderCount] = ridden == null ? -1 : ridden.getEntityId();
            frameOccluderCount++;
        }
    }

    /**
     * Donne à une lumière les boîtes qui l'arrêtent, parmi celles de l'image : celles qu'elle rencontre vraiment,
     * les plus proches d'abord. Son faisceau s'y arrête et ce qui est derrière reste dans leur ombre, sans que rien
     * ne soit dessiné sur elles.
     */
    private static void collectOccluders(LightFxEntry entry) {
        entry.occluderCount = 0;
        float reach = occluderReach(entry);
        if (reach < 1 || frameOccluderCount == 0) {
            return;
        }
        float reachSq = reach * reach;
        for (int box = 0; box < frameOccluderCount; box++) {
            if (FRAME_OCCLUDER_IDS[box] == entry.ownerEntityId
                    || FRAME_OCCLUDER_RIDING[box] == entry.ownerEntityId) {
                continue;
            }
            int at = box * 6;
            double minX = FRAME_OCCLUDERS[at], minY = FRAME_OCCLUDERS[at + 1], minZ = FRAME_OCCLUDERS[at + 2];
            double maxX = FRAME_OCCLUDERS[at + 3], maxY = FRAME_OCCLUDERS[at + 4], maxZ = FRAME_OCCLUDERS[at + 5];
            if (entry.emitX > minX && entry.emitX < maxX && entry.emitY > minY && entry.emitY < maxY
                    && entry.emitZ > minZ && entry.emitZ < maxZ) {
                // La lampe est à l'intérieur de cette boîte : quoi que ce soit, la lumière ne la rencontre pas
                continue;
            }
            // La distance de la lampe à la boîte, qui écarte celles hors de portée et ordonne celles gardées
            double awayX = Math.max(minX - entry.emitX, Math.max(0, entry.emitX - maxX));
            double awayY = Math.max(minY - entry.emitY, Math.max(0, entry.emitY - maxY));
            double awayZ = Math.max(minZ - entry.emitZ, Math.max(0, entry.emitZ - maxZ));
            double awaySq = awayX * awayX + awayY * awayY + awayZ * awayZ;
            if (awaySq > reachSq) {
                continue;
            }
            if (entry.spot && !inFrontOfSpot(entry, minX, minY, minZ, maxX, maxY, maxZ)) {
                // Un projecteur n'éclaire que devant lui : écarter ce qui est derrière évite des recherches vaines
                continue;
            }
            insertOccluder(entry, at, awaySq);
        }
    }

    /**
     * Insère une boîte dans la liste de celles qui arrêtent une lumière, triée par distance à la lampe : une
     * lumière n'en garde qu'une poignée, et les plus proches ombrent bien plus que celles au bout de sa portée.
     *
     * @param from L'emplacement de la boîte dans {@link DynamXLightFx#FRAME_OCCLUDERS}
     */
    private static void insertOccluder(LightFxEntry entry, int from, double awaySq) {
        int slot = entry.occluderCount;
        if (slot == LightFxEntry.MAX_OCCLUDERS) {
            if (awaySq >= entry.occluderDistanceSq[slot - 1]) {
                // Plus loin que toutes celles déjà gardées, et plus de place
                return;
            }
            slot--;
        } else {
            entry.occluderCount++;
        }
        while (slot > 0 && entry.occluderDistanceSq[slot - 1] > awaySq) {
            System.arraycopy(entry.occluders, (slot - 1) * 6, entry.occluders, slot * 6, 6);
            entry.occluderDistanceSq[slot] = entry.occluderDistanceSq[slot - 1];
            slot--;
        }
        System.arraycopy(FRAME_OCCLUDERS, from, entry.occluders, slot * 6, 6);
        entry.occluderDistanceSq[slot] = awaySq;
    }

    /**
     * @return Vrai si une partie de la boîte est devant la lampe d'un projecteur, la seule moitié du monde que son
     * faisceau atteint
     */
    private static boolean inFrontOfSpot(LightFxEntry entry, double minX, double minY, double minZ,
                                         double maxX, double maxY, double maxZ) {
        double towardsX = (minX + maxX) / 2 - entry.emitX;
        double towardsY = (minY + maxY) / 2 - entry.emitY;
        double towardsZ = (minZ + maxZ) / 2 - entry.emitZ;
        // La portée de la boîte le long du faisceau : ses demi-dimensions pondérées par la direction du faisceau
        double extent = (maxX - minX) / 2 * Math.abs(entry.beamX) + (maxY - minY) / 2 * Math.abs(entry.beamY)
                + (maxZ - minZ) / 2 * Math.abs(entry.beamZ);
        return towardsX * entry.beamX + towardsY * entry.beamY + towardsZ * entry.beamZ + extent > 0;
    }

    /**
     * Trace le trajet de la lumière d'une lampe entre deux points, contre les blocs et contre les entités. Où elle
     * a atterri est laissé dans {@link DynamXLightFx#traceX}, la face dans {@link DynamXLightFx#traceFace} (null si
     * elle a atteint sa cible), et si cette face est celle d'une entité dans {@link DynamXLightFx#traceEntity}.
     *
     * @return La fraction du trajet parcourue, 1 si rien ne l'a arrêtée
     */
    private static float traceLight(World world, LightFxEntry entry, double fromX, double fromY, double fromZ,
                                    double toX, double toY, double toZ) {
        traceFace = null;
        traceEntity = false;
        double dx = toX - fromX, dy = toY - fromY, dz = toZ - fromZ;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        traceX = toX;
        traceY = toY;
        traceZ = toZ;
        if (length < 1e-4) {
            return 1;
        }
        float travelled = traceBlocks(world, fromX, fromY, fromZ, dx, dy, dz, 1);
        if (travelled < 1) {
            traceFace = blockFace;
        }
        for (int box = 0; box < entry.occluderCount; box++) {
            float atBox = traceOccluder(entry, box, fromX, fromY, fromZ, dx, dy, dz);
            if (!Float.isNaN(atBox) && atBox < travelled) {
                travelled = atBox;
                traceFace = boxFace;
                traceEntity = true;
            }
        }
        if (travelled < 1) {
            traceX = fromX + dx * travelled;
            traceY = fromY + dy * travelled;
            traceZ = fromZ + dz * travelled;
        }
        return travelled;
    }

    /**
     * Lance un rayon à travers les blocs du monde, jusqu'au premier qui arrête la lumière. Le rayon est parcouru
     * bloc par bloc, en traversant à chaque pas la première face du bloc courant sur son trajet, puis la boîte de
     * chaque bloc est testée, pour qu'une dalle ou une barrière arrête la lumière là où elle se trouve vraiment.
     * Écrit ainsi plutôt que sur {@link World#rayTraceBlocks} car il est lancé pour chaque point de lumière posé
     * sur une surface, plusieurs centaines de fois par image : il n'alloue rien.
     *
     * @param limit Jusqu'où regarder, en fraction du rayon : un rayon visant une surface n'est tracé que jusqu'à
     *              juste avant elle, sinon cette surface arrêterait la lumière qu'on y dessine
     * @return La fraction parcourue avant d'être arrêté, ou 1 sinon. Le bloc est laissé dans
     * {@link DynamXLightFx#blockX} et la face dans {@link DynamXLightFx#blockFace}.
     */
    private static float traceBlocks(World world, double fromX, double fromY, double fromZ,
                                     double dx, double dy, double dz, double limit) {
        blockFace = null;
        int x = MathHelper.floor(fromX), y = MathHelper.floor(fromY), z = MathHelper.floor(fromZ);
        int stepX = dx > 0 ? 1 : -1, stepY = dy > 0 ? 1 : -1, stepZ = dz > 0 ? 1 : -1;
        // Ce qu'un bloc coûte au rayon sur chaque axe, et ce qui lui reste avant de passer dans le bloc suivant
        double deltaX = dx == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dx);
        double deltaY = dy == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dy);
        double deltaZ = dz == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dz);
        double nextX = dx == 0 ? Double.POSITIVE_INFINITY : (dx > 0 ? x + 1 - fromX : fromX - x) * deltaX;
        double nextY = dy == 0 ? Double.POSITIVE_INFINITY : (dy > 0 ? y + 1 - fromY : fromY - y) * deltaY;
        double nextZ = dz == 0 ? Double.POSITIVE_INFINITY : (dz > 0 ? z + 1 - fromZ : fromZ - z) * deltaZ;
        double travelled = 0;
        for (int step = 0; step < MAX_TRACE_STEPS && travelled <= limit; step++) {
            int terrain = terrainAt(world, x, y, z);
            if (terrain < 0) {
                // Hors du monde, ou hors des chunks chargés : rien de connu sur le passage
                break;
            }
            // Ce dans quoi un joueur se cognerait arrête la lumière ; l'eau et l'herbe la laissent passer
            int shape = terrain & 3;
            if (shape != TERRAIN_CLEAR) {
                double boxMinX = x, boxMinY = y, boxMinZ = z, boxMaxX = x + 1, boxMaxY = y + 1, boxMaxZ = z + 1;
                if (shape == TERRAIN_BOX) {
                    // Dalle, marche, barrière : le seul cas que la table ne résout pas, leur boîte dépendant des
                    // voisins
                    IBlockState state = blockAt(world, x, y, z);
                    if (state == null) {
                        break;
                    }
                    TRACE_POS.setPos(x, y, z);
                    AxisAlignedBB box = state.getCollisionBoundingBox(world, TRACE_POS);
                    if (box == null) {
                        // Elle vient de perdre sa boîte : finalement rien sur le passage
                        shape = TERRAIN_CLEAR;
                    } else {
                        boxMinX = x + box.minX;
                        boxMinY = y + box.minY;
                        boxMinZ = z + box.minZ;
                        boxMaxX = x + box.maxX;
                        boxMaxY = y + box.maxY;
                        boxMaxZ = z + box.maxZ;
                    }
                }
                if (shape != TERRAIN_CLEAR) {
                    float at = rayEntersBox(boxMinX, boxMinY, boxMinZ, boxMaxX, boxMaxY, boxMaxZ,
                            fromX, fromY, fromZ, dx, dy, dz);
                    if (!Float.isNaN(at) && at <= limit) {
                        blockFace = boxFace;
                        blockX = x;
                        blockY = y;
                        blockZ = z;
                        return at;
                    }
                }
            }
            // Passe dans le bloc suivant, par celle des trois faces devant lui que le rayon atteint en premier
            if (nextX < nextY) {
                if (nextX < nextZ) {
                    travelled = nextX;
                    x += stepX;
                    nextX += deltaX;
                } else {
                    travelled = nextZ;
                    z += stepZ;
                    nextZ += deltaZ;
                }
            } else if (nextY < nextZ) {
                travelled = nextY;
                y += stepY;
                nextY += deltaY;
            } else {
                travelled = nextZ;
                z += stepZ;
                nextZ += deltaZ;
            }
        }
        return 1;
    }

    /**
     * Dit quelle part de la lumière d'une lampe atteint vraiment un point du monde, contre les blocs comme contre
     * les entités : c'est ce qui garde la lumière d'un projecteur du bon côté du mur d'où elle est projetée.
     *
     * @param tolerance La part de la fin du rayon à ignorer, en blocs : le point est sur une surface, et le rayon
     *                  l'atteint en longeant les blocs auxquels elle appartient
     * @return La part qui passe, entre 0 et 1 : les blocs l'arrêtent ou non, une entité a un bord d'ombre adouci
     */
    private static float visibility(World world, LightFxEntry entry, double toX, double toY, double toZ,
                                    float tolerance) {
        double dx = toX - entry.emitX, dy = toY - entry.emitY, dz = toZ - entry.emitZ;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length <= tolerance) {
            return 1;
        }
        double limit = (length - tolerance) / length;
        // Les entités d'abord : elles ne coûtent que de l'arithmétique, là où les blocs sont lus dans le monde
        float visible = boxVisibility(entry, dx, dy, dz, limit);
        if (visible <= 0) {
            return 0;
        }
        return traceBlocks(world, entry.emitX, entry.emitY, entry.emitZ, dx, dy, dz, limit) >= limit
                ? visible : 0;
    }

    /**
     * @return La part de la lumière d'une lampe qui passe les entités sur son trajet jusqu'au point donné
     * @see DynamXLightFx#boxVisibility
     */
    private static float entityVisibility(LightFxEntry entry, double x, double y, double z) {
        if (entry.occluderCount == 0) {
            return 1;
        }
        return boxVisibility(entry, x - entry.emitX, y - entry.emitY, z - entry.emitZ, 1);
    }

    /**
     * Calcule la part de la lumière d'une lampe qui passe les boîtes des entités, le long du rayon menant à un
     * point : un rayon qui entre dans une boîte n'obtient rien, un rayon qui la frôle revient progressivement sur
     * {@link DynamXLightFx#SHADOW_SOFTNESS}. Ces boîtes bougent à chaque image : répondu en tout ou rien, chaque
     * point autour d'un véhicule s'allume et s'éteint quand la boîte passe dessus, alors qu'un fondu sur une
     * fraction de bloc ressemble à un vrai bord d'ombre et ne peut pas scintiller.
     *
     * @param limit Jusqu'où le long du rayon une boîte compte encore, en fraction de celui-ci
     */
    private static float boxVisibility(LightFxEntry entry, double dx, double dy, double dz, double limit) {
        float visible = 1;
        for (int box = 0; box < entry.occluderCount; box++) {
            float margin = rayMarginToBox(entry, box, dx, dy, dz, limit);
            if (margin > 0) {
                float at = traceOccluder(entry, box, entry.emitX, entry.emitY, entry.emitZ, dx, dy, dz);
                if (!Float.isNaN(at) && at < limit) {
                    // Le rayon entre vraiment dans cette boîte, aussi loin de son milieu qu'il passe : il est au
                    // moins sur le bord de l'ombre, et jamais plus qu'à moitié éclairé
                    margin = 0;
                }
            }
            // À moitié éclairé pile sur le bord de l'ombre, dedans une demi-douceur à l'intérieur, dégagé une
            // demi-douceur à l'extérieur : le bord adouci chevauche celui de la boîte au lieu de l'élargir
            if (margin < SHADOW_SOFTNESS / 2) {
                visible = Math.min(visible, MathHelper.clamp(0.5f + margin / SHADOW_SOFTNESS, 0f, 1f));
                if (visible <= 0) {
                    return 0;
                }
            }
        }
        return visible;
    }

    /**
     * Mesure à quelle distance un rayon passe du bord d'une des boîtes qui l'arrêtent, soit à quelle distance de
     * son ombre se trouve le point visé.
     *
     * @return Cette distance, en blocs, négative pour un rayon passant dans la boîte
     */
    private static float rayMarginToBox(LightFxEntry entry, int box, double dx, double dy, double dz,
                                        double limit) {
        double[] boxes = entry.occluders;
        int at = box * 6;
        double towardsX = (boxes[at] + boxes[at + 3]) / 2 - entry.emitX;
        double towardsY = (boxes[at + 1] + boxes[at + 4]) / 2 - entry.emitY;
        double towardsZ = (boxes[at + 2] + boxes[at + 5]) / 2 - entry.emitZ;
        double lengthSq = dx * dx + dy * dy + dz * dz;
        // Où le long du rayon la boîte est croisée, soit la projection de son milieu sur le rayon
        double along = lengthSq < 1e-9 ? 0
                : MathHelper.clamp((towardsX * dx + towardsY * dy + towardsZ * dz) / lengthSq, 0, limit);
        double atX = entry.emitX + dx * along, atY = entry.emitY + dy * along, atZ = entry.emitZ + dz * along;
        // Et de combien ce point du rayon est hors de la boîte, axe par axe
        double outX = Math.max(boxes[at] - atX, Math.max(0, atX - boxes[at + 3]));
        double outY = Math.max(boxes[at + 1] - atY, Math.max(0, atY - boxes[at + 4]));
        double outZ = Math.max(boxes[at + 2] - atZ, Math.max(0, atZ - boxes[at + 5]));
        if (outX > 0 || outY > 0 || outZ > 0) {
            return (float) Math.sqrt(outX * outX + outY * outY + outZ * outZ);
        }
        // Dans la boîte : de combien il lui manque pour en sortir, ce qui prolonge le bord adouci à travers elle
        double inside = Math.min(Math.min(atX - boxes[at], boxes[at + 3] - atX),
                Math.min(Math.min(atY - boxes[at + 1], boxes[at + 4] - atY),
                        Math.min(atZ - boxes[at + 2], boxes[at + 5] - atZ)));
        return (float) -inside;
    }

    /** @return Vrai si le bloc à cette position est assez solide pour recevoir et arrêter la lumière d'une lampe */
    private static boolean catchesLight(World world, int x, int y, int z) {
        int terrain = terrainAt(world, x, y, z);
        return terrain > 0 && (terrain & 3) != TERRAIN_CLEAR;
    }

    /**
     * Lit un bloc du monde, par le chunk du bloc précédent quand ils le partagent
     *
     * @return L'état du bloc, ou null hors du monde ou dans un chunk non chargé
     */
    @Nullable
    /**
     * Ce qu'un bloc du terrain fait à la lumière, pris dans la table des blocs déjà examinés : ce que coûte la
     * lecture est de demander sa boîte de collision au bloc, que bien des blocs construisent à la demande. La boîte
     * d'un bloc qui en a une propre n'est pas gardée, ce sont celles qui dépendent des voisins.
     *
     * @return {@link DynamXLightFx#TERRAIN_CLEAR}, {@link DynamXLightFx#TERRAIN_FULL} ou
     * {@link DynamXLightFx#TERRAIN_BOX}, plus {@link DynamXLightFx#TERRAIN_OPAQUE} si le bloc masque ce qui est
     * derrière, ou -1 hors du monde
     */
    private static int terrainAt(World world, int x, int y, int z) {
        if (y < 0 || y > 255) {
            return -1;
        }
        int key = blockKey(x, y, z);
        int slot = key == 0 ? -1 : keySlot(BLOCK_KEYS, BLOCK_STAMPS, BLOCK_KEY_MASK, groundStamp, key);
        if (slot >= 0 && BLOCK_STAMPS[slot] == groundStamp) {
            return BLOCK_TERRAIN[slot];
        }
        IBlockState state = blockAt(world, x, y, z);
        if (state == null) {
            // Non gardé : le chunk peut être là la prochaine fois qu'on le demande
            return -1;
        }
        byte terrain = TERRAIN_CLEAR;
        if (state.getMaterial() != Material.AIR) {
            TERRAIN_POS.setPos(x, y, z);
            AxisAlignedBB box = state.getCollisionBoundingBox(world, TERRAIN_POS);
            if (box != null) {
                // Exactement tout son bloc, et pas seulement aussi gros : sinon il faut demander la boîte entière
                terrain = box.minX == 0 && box.minY == 0 && box.minZ == 0
                        && box.maxX == 1 && box.maxY == 1 && box.maxZ == 1 ? TERRAIN_FULL : TERRAIN_BOX;
            }
            if (state.isOpaqueCube()) {
                terrain |= TERRAIN_OPAQUE;
            }
        }
        if (slot >= 0) {
            BLOCK_STAMPS[slot] = groundStamp;
            BLOCK_KEYS[slot] = key;
            BLOCK_TERRAIN[slot] = terrain;
        }
        return terrain;
    }

    /**
     * @return La clé d'un bloc dans cette table, ou 0 pour un bloc qu'elle ne peut pas tenir. Les coordonnées sont
     * réduites à mille blocs, bien au-delà de ce que le renderer lit, donc deux blocs ne partagent jamais une clé.
     */
    private static int blockKey(int x, int y, int z) {
        return 1 + ((((x & 1023) << 8 | (y & 255)) << 10) | (z & 1023));
    }

    private static IBlockState blockAt(World world, int x, int y, int z) {
        if (y < 0 || y > 255) {
            return null;
        }
        Chunk chunk = chunkAt(world, x, z);
        return chunk == null ? null : chunk.getBlockState(x, y, z);
    }

    /**
     * @return Le chunk de cette colonne, ou null si le client ne l'a pas. Gardé dans quelques cases : tout ce que
     * le renderer lit est à quelques chunks de la lampe concernée.
     */
    private static Chunk chunkAt(World world, int x, int z) {
        int chunkX = x >> 4, chunkZ = z >> 4;
        for (int slot = 0; slot < CACHED_CHUNKS; slot++) {
            if (CACHED_CHUNK_X[slot] == chunkX && CACHED_CHUNK_Z[slot] == chunkZ) {
                return CACHED_CHUNKS_AT[slot];
            }
        }
        // Seulement les chunks déjà chargés : en demander un absent au client en construirait un vide. Un chunk
        // absent est gardé quand même, pour ne pas le redemander à chaque rayon sortant du monde de ce côté.
        Chunk chunk = world.getChunkProvider().getLoadedChunk(chunkX, chunkZ);
        int slot = nextCachedChunk;
        nextCachedChunk = slot + 1 == CACHED_CHUNKS ? 0 : slot + 1;
        CACHED_CHUNKS_AT[slot] = chunk;
        CACHED_CHUNK_X[slot] = chunkX;
        CACHED_CHUNK_Z[slot] = chunkZ;
        return chunk;
    }

    /**
     * Lance un rayon sur une des boîtes qui arrêtent la lumière d'une lampe
     *
     * @return La fraction du rayon parcourue avant d'entrer dans la boîte, ou NaN s'il n'y entre jamais
     */
    private static float traceOccluder(LightFxEntry entry, int box, double fromX, double fromY, double fromZ,
                                       double dx, double dy, double dz) {
        double[] boxes = entry.occluders;
        int at = box * 6;
        return rayEntersBox(boxes[at], boxes[at + 1], boxes[at + 2], boxes[at + 3], boxes[at + 4], boxes[at + 5],
                fromX, fromY, fromZ, dx, dy, dz);
    }

    /**
     * Lance un rayon sur une boîte, celle d'une entité ou celle d'un bloc : intersection par tranches classique, le
     * rayon est découpé contre les deux plans de la boîte sur chaque axe, et l'axe qui a découpé son début en
     * dernier donne la face d'entrée, celle qui reçoit la lumière. Écrit sur des nombres bruts plutôt que sur
     * {@link AxisAlignedBB#calculateIntercept}, car cela tourne pour chaque bloc de chaque rayon.
     *
     * @return La fraction parcourue avant d'entrer dans la boîte, ou NaN s'il n'y entre jamais, et la face d'entrée
     * dans {@link DynamXLightFx#boxFace}. Un rayon partant dans la boîte n'y entre pas.
     */
    private static float rayEntersBox(double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                                      double fromX, double fromY, double fromZ, double dx, double dy, double dz) {
        double enter = 0, leave = 1;
        boxFace = null;
        if (Math.abs(dx) < 1e-9) {
            if (fromX < minX || fromX > maxX) {
                return Float.NaN;
            }
        } else {
            double inverse = 1 / dx;
            double near = (minX - fromX) * inverse, far = (maxX - fromX) * inverse;
            EnumFacing face = ENTRY_FACES[dx > 0 ? 0 : 1];
            if (near > far) {
                double swap = near;
                near = far;
                far = swap;
            }
            if (near > enter) {
                enter = near;
                boxFace = face;
            }
            leave = Math.min(leave, far);
            if (enter > leave) {
                return Float.NaN;
            }
        }
        if (Math.abs(dy) < 1e-9) {
            if (fromY < minY || fromY > maxY) {
                return Float.NaN;
            }
        } else {
            double inverse = 1 / dy;
            double near = (minY - fromY) * inverse, far = (maxY - fromY) * inverse;
            EnumFacing face = ENTRY_FACES[dy > 0 ? 2 : 3];
            if (near > far) {
                double swap = near;
                near = far;
                far = swap;
            }
            if (near > enter) {
                enter = near;
                boxFace = face;
            }
            leave = Math.min(leave, far);
            if (enter > leave) {
                return Float.NaN;
            }
        }
        if (Math.abs(dz) < 1e-9) {
            if (fromZ < minZ || fromZ > maxZ) {
                return Float.NaN;
            }
        } else {
            double inverse = 1 / dz;
            double near = (minZ - fromZ) * inverse, far = (maxZ - fromZ) * inverse;
            EnumFacing face = ENTRY_FACES[dz > 0 ? 4 : 5];
            if (near > far) {
                double swap = near;
                near = far;
                far = swap;
            }
            if (near > enter) {
                enter = near;
                boxFace = face;
            }
            leave = Math.min(leave, far);
            if (enter > leave) {
                return Float.NaN;
            }
        }
        // Un rayon dont le début n'a jamais été découpé partait dans la boîte
        return boxFace == null ? Float.NaN : (float) enter;
    }

    /* Dessin des effets */

    /** Dessine le halo de la lampe elle-même, en billboard face à la caméra */
    private static void renderFlare(BufferBuilder buffer, LightFxEntry entry) {
        double distance = Math.sqrt(entry.cameraDistanceSq);
        // Une lampe presque sur la caméra (en première personne) couvrirait l'écran d'un billboard additif
        float nearFade = nearFade(distance, NEAR_FADE_END, NEAR_FADE_START);
        if (nearFade <= 0) {
            return;
        }
        // Le halo est dimensionné sur la lampe et grandit un peu avec la distance, pour rester visible de loin. Un
        // gyrophare en reçoit plus que sa taille ne lui donnerait : sa lampe est tout l'effet.
        float sizeBoost = entry.beacon ? BEACON_FLARE_SIZE : 1;
        float lampSize = Math.max(entry.lampHalfWidth, MIN_FLARE_SIZE) * FLARE_SIZE * sizeBoost;
        float size = Math.min(lampSize * (1 + (float) distance * FLARE_GROWTH), MAX_FLARE_SIZE * sizeBoost);
        float alpha = Math.min(1, FLARE_ALPHA * (entry.beacon ? BEACON_FLARE_BOOST : 1)
                * entry.brightness * nearFade);
        // Et atténué d'exactement ce dont il a été étalé : le halo ajoute de la lumière, donc ce qu'il ajoute suit
        // la surface couverte, sinon il éclairerait de plus en plus à mesure que le joueur s'éloigne
        float spread = Math.min(1, lampSize / size);
        alpha *= spread * spread;
        if (entry.sweeping) {
            // Un gyrophare flashe quand un de ses miroirs se tourne vers le spectateur, ce qui fait tout l'effet
            float toCamera = (float) Math.atan2(cameraZ - entry.z, cameraX - entry.x);
            float beamAngle = (float) Math.atan2(entry.beamZ, entry.beamX);
            alpha *= BEACON_FLARE_FLOOR + (1 - BEACON_FLARE_FLOOR)
                    * beamWeight(entry, toCamera - beamAngle, FLARE_SWEEP_SPREAD);
        } else if (entry.spot) {
            // Un projecteur est aveuglant de face et simplement allumé de dos, la vue habituelle en conduisant
            float facing = (float) ((entry.beamX * (cameraX - entry.x) + entry.beamY * (cameraY - entry.y)
                    + entry.beamZ * (cameraZ - entry.z)) / distance);
            alpha *= SPOT_FLARE_FROM_BEHIND + (1 - SPOT_FLARE_FROM_BEHIND)
                    * MathHelper.clamp((facing + 0.3f) / 1.3f, 0f, 1f);
        }
        if (alpha <= MIN_VISIBLE) {
            return;
        }
        // Avance un peu le halo vers la caméra, pour que la lampe ne le cache pas
        double factor = FLARE_LIFT / distance;
        double x = entry.x + (cameraX - entry.x) * factor;
        double y = entry.y + (cameraY - entry.y) * factor;
        double z = entry.z + (cameraZ - entry.z) * factor;

        // Toute la lampe brille : une rampe est couverte d'une rangée de halos serrés, lue comme un bandeau
        float halfSpan = MathHelper.sqrt(entry.glowSpanX * entry.glowSpanX + entry.glowSpanY * entry.glowSpanY
                + entry.glowSpanZ * entry.glowSpanZ);
        // Une lampe multicolore est découpée en suites de tronçons d'une même couleur : les modules rouges d'une
        // rampe brillent alors en rouge et ses bleus en bleu. Une lampe unie fait une seule suite.
        int stretches = entry.glowColors == null ? 1
                : Math.min(entry.glowColors.length, LightFxLamp.COLOR_STRETCHES);
        int runs = 0;
        FLARE_RUN_START[0] = 0;
        for (int stretch = 0; stretch < stretches; stretch++) {
            // La suite s'arrête où la couleur cesse d'être celle de son début
            if (stretch + 1 < stretches
                    && LightColorResolver.sameColor(entry.glowColors[stretch + 1], entry.glowColors[FLARE_RUN_START[runs]])) {
                continue;
            }
            FLARE_RUN_END[runs++] = stretch;
            if (stretch + 1 < stretches) {
                FLARE_RUN_START[runs] = stretch + 1;
            }
        }
        // Le budget de halos est partagé entre les suites : une rampe de huit modules garde un halo par module
        int perRun = Math.max(1, MAX_FLARE_STEPS / runs);
        int totalSteps = 0;
        for (int run = 0; run < runs; run++) {
            float runHalfSpan = halfSpan * (FLARE_RUN_END[run] + 1 - FLARE_RUN_START[run]) / stretches;
            // Un halo par largeur de halo sur toute la suite, soit deux fois cette demi-longueur
            FLARE_RUN_STEPS[run] = MathHelper.clamp(
                    MathHelper.ceil(2 * runHalfSpan / Math.max(size, 0.05f)), 1, perRun);
            totalSteps += FLARE_RUN_STEPS[run];
        }
        if (totalSteps > 1) {
            // Les voisins se chevauchent, et leur somme serait plus brillante que la lampe là où ils se touchent
            alpha *= FLARE_OVERLAP_DAMPING;
        }
        for (int run = 0; run < runs; run++) {
            // Où cette suite d'une même couleur commence et finit, en fraction de l'étalement du halo
            float from = 2f * FLARE_RUN_START[run] / stretches - 1;
            float to = 2f * (FLARE_RUN_END[run] + 1) / stretches - 1;
            float red = entry.red, green = entry.green, blue = entry.blue;
            if (entry.glowColors != null) {
                int color = entry.glowColors[FLARE_RUN_START[run]];
                red = ((color >> 16) & 0xFF) / 255f;
                green = ((color >> 8) & 0xFF) / 255f;
                blue = (color & 0xFF) / 255f;
            }
            int steps = FLARE_RUN_STEPS[run];
            for (int step = 0; step < steps; step++) {
                // La suite est découpée en autant de tronçons égaux qu'il y a de halos, chacun au milieu du sien :
                // répartis d'un bout à l'autre, les extérieurs seraient à moitié hors de la lampe
                float along = from + (to - from) * (2f * step + 1) / (2 * steps);
                double stepX = x + entry.glowSpanX * along;
                double stepY = y + entry.glowSpanY * along;
                double stepZ = z + entry.glowSpanZ * along;
                // Un voile très faible autour d'un petit coeur brillant, qui se lisent comme une lampe allumée
                billboard(buffer, entry, stepX, stepY, stepZ, size * FLARE_BLOOM_SIZE, alpha * FLARE_BLOOM_ALPHA,
                        red, green, blue);
                billboard(buffer, entry, stepX, stepY, stepZ, size * FLARE_CORE_SIZE, alpha, red, green, blue);
            }
        }
    }

    /* Projection d'un phare sur le terrain devant lui */

    /**
     * Calcule jusqu'où et sur quelle largeur la lumière d'un projecteur porte au sol. Elle n'est jamais coupée en
     * bloc : ce qui en masque une partie est traité direction par direction au moment de la pose.
     */
    private static void resolveProjection(LightFxEntry entry) {
        float radius = Math.min(entry.groundRadius, MAX_POOL_RADIUS);
        if (radius < 0.5f) {
            // Ce projecteur ne pose aucune lumière au sol
            return;
        }
        float horizontal = MathHelper.sqrt(entry.beamX * entry.beamX + entry.beamZ * entry.beamZ);
        if (horizontal < 0.1f) {
            // Le projecteur vise droit en haut ou en bas : aucune route devant lui à éclairer
            return;
        }
        float widthFar = radius * SPOT_WIDTH_FAR;
        float length = Math.min(entry.radius * SPOT_THROW, MAX_BEAM_LENGTH);
        // Elle porte aussi loin que le projecteur : ce qui en masque une partie est traité bloc par bloc
        entry.projectionLength = length;
        // Elle part aussi large que la lampe qui la projette, et s'évase de là
        entry.projectionWidthNear = Math.min(entry.lampHalfWidth, widthFar * SPOT_MAX_NEAR_RATIO);
        entry.projectionWidthFar = widthFar;
    }

    /**
     * Dessine la lumière qu'un projecteur pose sur le terrain devant lui, sur le dessus des blocs, un bloc à la
     * fois, sur toute l'empreinte du faisceau : chaque colonne couverte est examinée, la surface où la lumière
     * atterrirait y est cherchée, et la part qui atteint ses quatre coins est calculée. Les blocs voisins partagent
     * leurs coins, donc la lumière passe sans couture de l'un à l'autre. <br>
     * Posée sur les blocs et non en une nappe tendue sur les hauteurs du terrain, car une nappe ne colle qu'à un
     * sol plat et traverse sinon les blocs qu'elle est censée éclairer. <br>
     * Qu'un point soit atteint est demandé au monde, coin par coin, mais seulement quand la réponse n'est pas
     * connue d'avance : un sol de niveau avec celui sous la lampe, sans rien dans le faisceau, est forcément
     * atteint, et un phare sur une route dégagée ne paie donc rien de tout cela.
     *
     * @see DynamXLightFx#computeCornerLight
     */
    private static void renderSpotGround(BufferBuilder buffer, World world, LightFxEntry entry) {
        float length = entry.projectionLength;
        if (length <= 0) {
            return;
        }
        float horizontal = MathHelper.sqrt(entry.beamX * entry.beamX + entry.beamZ * entry.beamZ);
        if (horizontal < 0.1f) {
            // Le projecteur vise droit en haut ou en bas : aucune route devant lui à éclairer
            return;
        }
        float alpha = SURFACE_ALPHA * entry.brightness * entry.projectionFade;
        if (alpha <= MIN_VISIBLE) {
            return;
        }
        // Repère horizontal du faisceau : sa direction, et l'axe en travers
        float alongX = entry.beamX / horizontal, alongZ = entry.beamZ / horizontal;
        float acrossX = -alongZ, acrossZ = alongX;
        // Le sol sous la lampe elle-même, avec lequel le reste du terrain est de niveau ou non
        int maxDrop = MathHelper.ceil(length) + GROUND_EXTRA_DROP;
        int fromY = MathHelper.floor(entry.emitY) + 1;
        groundLevel = groundBelow(world, MathHelper.floor(entry.emitX), MathHelper.floor(entry.emitZ),
                fromY, maxDrop);
        if (Float.isNaN(groundLevel)) {
            // La lampe n'est au-dessus d'aucun sol : rien à éclairer
            return;
        }
        beginGroundFaces(entry, alongX, alongZ, length);
        // Un obstacle dressé dans le faisceau rend le terrain au-delà digne d'être tracé : c'est la seule chose
        // qui puisse ombrer un sol plat. Mesuré le long du faisceau, pas en tout ou rien.
        groundTracedFrom = entry.projectionBlockedFrom;
        // L'empreinte est un trapèze orienté comme le véhicule : les blocs dessous sont parcourus sur le rectangle
        // qui l'entoure, chacun exactement une fois
        double spanNearX = acrossX * entry.projectionWidthNear, spanNearZ = acrossZ * entry.projectionWidthNear;
        double spanFarX = acrossX * entry.projectionWidthFar, spanFarZ = acrossZ * entry.projectionWidthFar;
        double farX = entry.emitX + alongX * length, farZ = entry.emitZ + alongZ * length;
        // Les quatre coins de ce trapèze sont les deux côtés de la lampe et les deux du bout du faisceau
        int minX = MathHelper.floor(Math.min(Math.min(entry.emitX - spanNearX, entry.emitX + spanNearX),
                Math.min(farX - spanFarX, farX + spanFarX)));
        int maxX = MathHelper.floor(Math.max(Math.max(entry.emitX - spanNearX, entry.emitX + spanNearX),
                Math.max(farX - spanFarX, farX + spanFarX)));
        int minZ = MathHelper.floor(Math.min(Math.min(entry.emitZ - spanNearZ, entry.emitZ + spanNearZ),
                Math.min(farZ - spanFarZ, farZ + spanFarZ)));
        int maxZ = MathHelper.floor(Math.max(Math.max(entry.emitZ - spanNearZ, entry.emitZ + spanNearZ),
                Math.max(farZ - spanFarZ, farZ + spanFarZ)));
        // Une route dégagée : un sol plat de blocs pleins, rien dans le faisceau et rien dessus. Chaque coin porte
        // alors le même nombre, et tout cela fait un seul quad avec le dégradé dessus.
        int flatTopY = MathHelper.floor(groundLevel);
        if (entry.occluderCount == 0 && entry.projectionBlockedFrom == Float.MAX_VALUE
                && groundLevel == flatTopY && flatTopY < entry.emitY) {
            int columns = flatFootprint(world, entry, flatTopY, minX, maxX, minZ, maxZ);
            if (columns > 0) {
                // Comptées comme les blocs qu'elles représentent, pour rester bornées par le même budget
                frameGroundColumnsLeft -= columns;
                renderFlatGround(buffer, entry, alongX, alongZ, acrossX, acrossZ, flatTopY, alpha);
                frameFlatPools++;
                groundMode = false;
                return;
            }
        }
        frameBlockPools++;
        for (int blockX = minX; blockX <= maxX && frameGroundColumnsLeft > 0; blockX++) {
            if (!footprintRow(entry, blockX, minZ, maxZ)) {
                // L'empreinte n'atteint pas du tout cette ligne de blocs
                continue;
            }
            // Surface trouvée à la ligne précédente, juste au-dessus de laquelle la recherche suivante commence :
            // partir de la lampe lirait toute la colonne d'air sous elle pour chaque bloc de sol
            float previous = Float.NaN;
            for (int blockZ = rowFromZ; blockZ <= rowToZ; blockZ++) {
                if (!touchesFootprint(entry, blockX, blockZ)) {
                    // Bloc hors de l'empreinte : l'essentiel du rectangle parcouru est résolu ici
                    continue;
                }
                if (frameGroundColumnsLeft <= 0) {
                    // Les projecteurs plus proches ont déjà pris le budget : on pose sur ce qui a été trouvé
                    break;
                }
                frameGroundColumnsLeft--;
                // La surface la plus haute de la colonne, jamais au-dessus de la lampe
                int searchFrom = Float.isNaN(previous) ? fromY
                        : Math.min(fromY, MathHelper.floor(previous) + GROUND_SEARCH_RISE);
                float groundY = groundBelow(world, blockX, blockZ, searchFrom, maxDrop);
                if (Float.isNaN(groundY)) {
                    // Rien sous cette partie du faisceau : un trou profond, ou un chunk absent
                    continue;
                }
                previous = groundY;
                // La hauteur trouvée est le dessus de cette surface, dont le bloc est celui juste en dessous
                addLitFace(world, entry, blockX, MathHelper.floor(groundY - GROUND_SURFACE_BIAS), blockZ,
                        EnumFacing.UP, alpha);
            }
        }
        for (int face = 0; face < litFaceCount; face++) {
            drawLitFace(buffer, world, entry, face, alpha);
        }
        groundMode = false;
    }

    /**
     * Dit si toute l'empreinte d'un faisceau repose sur un sol plat de blocs pleins avec rien dessus : chaque
     * colonne doit porter un bloc plein juste sous la hauteur où se tient la lampe, et rien au-dessus. La lumière
     * tombe alors sur un seul plan, chaque coin est atteint en ligne droite, et la décroissance est le dégradé et
     * rien d'autre.
     *
     * @return Le nombre de colonnes que ce sol couvre, ou 0 si ce n'en est pas un
     */
    private static int flatFootprint(World world, LightFxEntry entry, int flatTopY, int minX, int maxX,
                                     int minZ, int maxZ) {
        int columns = 0;
        for (int blockX = minX; blockX <= maxX; blockX++) {
            if (!footprintRow(entry, blockX, minZ, maxZ)) {
                continue;
            }
            for (int blockZ = rowFromZ; blockZ <= rowToZ; blockZ++) {
                if (!touchesFootprint(entry, blockX, blockZ)) {
                    continue;
                }
                if (++columns > MAX_GROUND_FACES) {
                    // Plus large que ce qui est posé d'un coup : le parcours ci-dessous le coupera
                    return 0;
                }
                if ((terrainAt(world, blockX, flatTopY - 1, blockZ) & 3) != TERRAIN_FULL
                        || (terrainAt(world, blockX, flatTopY, blockZ) & 3) != TERRAIN_CLEAR) {
                    // Marche, dalle, bordure, trou, mur ou chunk absent : celle-ci est posée bloc par bloc
                    return 0;
                }
            }
        }
        return columns;
    }

    /**
     * Dessine la lumière qu'un projecteur pose sur un tel sol, en l'unique quad que son empreinte est vraiment :
     * quatre coins au lieu de quelques centaines de faces, et le dégradé tombe dessus exactement pareil, ses deux
     * coordonnées variant linéairement sur le sol.
     */
    private static void renderFlatGround(BufferBuilder buffer, LightFxEntry entry, float alongX, float alongZ,
                                         float acrossX, float acrossZ, int flatTopY, float alpha) {
        // Décollée du sol sur lequel elle repose, exactement comme toute autre face de lumière
        double y = flatTopY + SURFACE_OFFSET;
        double nearX = entry.emitX, nearZ = entry.emitZ;
        double farX = entry.emitX + alongX * groundLength, farZ = entry.emitZ + alongZ * groundLength;
        double spanNearX = acrossX * groundNear, spanNearZ = acrossZ * groundNear;
        double spanFarX = acrossX * groundFar, spanFarZ = acrossZ * groundFar;
        // Où son extrémité proche se trouve le long de l'empreinte, depuis le point de convergence des bords
        float nearAlong = -groundApex / (groundLength - groundApex);
        float nearV = LightFxTextures.PROFILE_ALONG_OFFSET + nearAlong * LightFxTextures.PROFILE_ALONG_SCALE;
        float farV = LightFxTextures.PROFILE_ALONG_OFFSET + LightFxTextures.PROFILE_ALONG_SCALE;
        float nearSide = groundNear / groundFar * LightFxTextures.PROFILE_SCALE;
        texturedVertex(buffer, nearX - spanNearX, y, nearZ - spanNearZ,
                LightFxTextures.PROFILE_CENTER - nearSide, nearV, entry, alpha);
        texturedVertex(buffer, nearX + spanNearX, y, nearZ + spanNearZ,
                LightFxTextures.PROFILE_CENTER + nearSide, nearV, entry, alpha);
        texturedVertex(buffer, farX + spanFarX, y, farZ + spanFarZ,
                LightFxTextures.PROFILE_CENTER + LightFxTextures.PROFILE_SCALE, farV, entry, alpha);
        texturedVertex(buffer, farX - spanFarX, y, farZ - spanFarZ,
                LightFxTextures.PROFILE_CENTER - LightFxTextures.PROFILE_SCALE, farV, entry, alpha);
    }

    /**
     * Dessine la lumière qu'un projecteur pose sur les surfaces dressées dans son faisceau : un mur, un bâtiment,
     * un tronc, le plafond d'un tunnel. Des rayons trouvent les faces atteintes, puis la lumière s'étend de chacune
     * aux faces qui prolongent la surface autour d'elle, chaque face trouvée étant dessinée en entier avec sa
     * lumière calculée à ses quatre coins. Travailler sur les faces des blocs est ce qui remplit un angle, deux
     * faces voisines partageant leur arête, et rend la couverture d'un mur entier gratuite. <br>
     * Chaque coin n'est éclairé que si la lampe s'y voit. Un véhicule dressé dans le faisceau ombre la surface
     * derrière lui sans rien recevoir : sa bounding box est un bloc et son modèle non.
     */
    private static void renderSpotSurfaces(BufferBuilder buffer, World world, LightFxEntry entry) {
        if (entry.projectionLength <= 0) {
            return;
        }
        float baseAlpha = SURFACE_ALPHA * entry.brightness * entry.projectionFade;
        if (baseAlpha <= MIN_VISIBLE) {
            return;
        }
        float horizontal = MathHelper.sqrt(entry.beamX * entry.beamX + entry.beamZ * entry.beamZ);
        if (horizontal < 0.1f) {
            return;
        }
        // Repère du faisceau : l'axe horizontal en travers, et l'axe vers le haut, produit des deux autres
        spotAcrossX = -entry.beamZ / horizontal;
        spotAcrossZ = entry.beamX / horizontal;
        spotUpX = -spotAcrossZ * entry.beamY;
        spotUpY = horizontal;
        spotUpZ = spotAcrossX * entry.beamY;
        // Les projecteurs proches couvrent des bâtiments ; les autres reçoivent le minimum garanti
        beginLitFaces(entry, MathHelper.clamp(frameSurfaceFacesLeft, MIN_SURFACE_FACES, MAX_LIT_FACES));
        // Les rayons n'ont qu'à trouver les surfaces : la lumière s'étend ensuite de face en face
        float length = entry.projectionLength;
        float widthFar = Math.max(entry.projectionWidthFar * SPOT_SEED_SPREAD,
                SPOT_MIN_SURFACE_SPREAD * SPOT_SEED_REACH);
        float heightFar = Math.max(entry.projectionWidthFar * SPOT_FLATTEN * SPOT_SEED_SPREAD,
                SPOT_MIN_SURFACE_SPREAD * SPOT_SEED_REACH);
        // Moins de rayons pour une lumière loin de la caméra, ce que le parcours des surfaces compense
        boolean fine = entry.cameraDistanceSq < frameSpotFineDistanceSq;
        int seedAcross = fine ? SPOT_SEED_ACROSS : SPOT_SEED_ACROSS / 2 + 1;
        int seedUp = fine ? SPOT_SEED_UP : SPOT_SEED_UP / 2 + 1;
        if (fine && seedAcross * seedUp > frameSeedRaysLeft) {
            // Les projecteurs plus proches ont pris l'essentiel des rayons : un éventail mince suffit
            seedAcross = SPOT_SEED_ACROSS / 2 + 1;
            seedUp = SPOT_SEED_UP / 2 + 1;
        }
        if (seedAcross * seedUp > frameSeedRaysLeft) {
            // Plus rien pour chercher les surfaces : ce projecteur n'éclaire que le sol devant lui
            entry.projectionBlockedFrom = Float.MAX_VALUE;
            return;
        }
        frameSeedRaysLeft -= seedAcross * seedUp;
        for (int across = 0; across < seedAcross; across++) {
            float u = -1 + 2f * across / (seedAcross - 1);
            for (int up = 0; up < seedUp; up++) {
                float w = -1 + 2f * up / (seedUp - 1);
                // Visé sur son propre point de la section lointaine, pour que les rayons s'évasent comme le faisceau
                double dx = entry.beamX * length + spotAcrossX * (u * widthFar) + spotUpX * (w * heightFar);
                double dy = entry.beamY * length + spotUpY * (w * heightFar);
                double dz = entry.beamZ * length + spotAcrossZ * (u * widthFar) + spotUpZ * (w * heightFar);
                // Contre les blocs seulement : un véhicule ne doit pas cacher le mur derrière lui
                float limit = Math.min(
                        seedLimit(Math.abs(u) * widthFar, entry.projectionWidthNear, entry.projectionWidthFar),
                        seedLimit(Math.abs(w) * heightFar, entry.projectionWidthNear * SPOT_FLATTEN,
                                entry.projectionWidthFar * SPOT_FLATTEN));
                float travelled = traceBlocks(world, entry.emitX, entry.emitY, entry.emitZ, dx, dy, dz, limit);
                if (travelled < 1 && blockFace != null
                        && addLitFace(world, entry, blockX, blockY, blockZ, blockFace, baseAlpha)) {
                    // La distance du premier obstacle dressé dans le faisceau, à partir de laquelle la lumière au
                    // sol vaut d'être tracée : tout ce qui est devant est éclairé à travers l'air libre
                    float along = (float) (travelled
                            * (dx * entry.beamX + dy * entry.beamY + dz * entry.beamZ));
                    entry.projectionBlockedFrom = Math.min(entry.projectionBlockedFrom, Math.max(0, along));
                }
            }
        }
        spreadLitFaces(world, entry, baseAlpha);
        frameSurfaceFacesLeft -= litFaceCount;
        for (int face = 0; face < litFaceCount; face++) {
            drawLitFace(buffer, world, entry, face, baseAlpha);
        }
    }

    /**
     * Dessine la tache qu'un faisceau de gyrophare pose là où il atterrit, sur les faces des blocs autour de ce
     * point comme la lumière d'un projecteur : elle reste donc sur la surface touchée, en suit les angles et
     * s'arrête où elle s'arrête, au lieu de déborder du bloc comme un sprite plat.
     */
    private static void renderBeamPatch(BufferBuilder buffer, World world, LightFxEntry entry, int beam) {
        float length = entry.beamLength[beam];
        float baseAlpha = SURFACE_ALPHA * entry.brightness
                * (1 - 0.6f * MathHelper.clamp(length / MAX_BEAM_LENGTH, 0f, 1f));
        if (baseAlpha <= MIN_VISIBLE) {
            return;
        }
        beginLitFaces(entry, MAX_PATCH_FACES);
        // La lumière d'un faisceau décroît avec la distance au point d'atterrissage, pas dans le faisceau
        patchRadius = Math.max(MIN_PATCH_RADIUS, 0.35f + length * 0.14f);
        patchX = entry.beamHitX[beam];
        patchY = entry.beamHitY[beam];
        patchZ = entry.beamHitZ[beam];
        addLitFace(world, entry, entry.beamHitBlockX[beam], entry.beamHitBlockY[beam], entry.beamHitBlockZ[beam],
                entry.beamHitFace[beam], baseAlpha);
        spreadLitFaces(world, entry, baseAlpha);
        for (int lit = 0; lit < litFaceCount; lit++) {
            drawLitFace(buffer, world, entry, lit, baseAlpha);
        }
    }

    /**
     * Jusqu'où un rayon cherchant les surfaces vaut d'être tracé, en fraction de lui-même : l'éventail est plus
     * large que le faisceau, et une fois un rayon sorti du faisceau il n'y a plus de lumière à trouver le long de
     * lui.
     *
     * @param offset Sa distance à l'axe du faisceau, là où il finit
     */
    private static float seedLimit(float offset, float near, float far) {
        if (offset <= far) {
            // Celui-ci ne sort jamais du faisceau
            return 1;
        }
        // Où il croise le bord du faisceau, ou la taille minimale de la lumière sur une surface
        float fanned = near / (offset - far + near);
        float floored = SPOT_MIN_SURFACE_SPREAD / offset;
        // Tracé un peu au-delà, pour trouver aussi les faces à cheval sur le bord de la lumière
        return Math.min(1, Math.max(fanned, floored) * 1.3f);
    }

    /**
     * Commence à collecter les faces sur lesquelles une lumière est posée
     *
     * @param budget Le nombre de faces que cette lumière peut éclairer au plus
     */
    private static void beginLitFaces(LightFxEntry entry, int budget) {
        litFaceCount = 0;
        visitedFaces = 0;
        faceBudget = budget;
        patchRadius = 0;
        groundMode = false;
        keyBaseX = MathHelper.floor(entry.emitX);
        keyBaseY = MathHelper.floor(entry.emitY);
        keyBaseZ = MathHelper.floor(entry.emitZ);
        // Vide les faces déjà rencontrées et les coins déjà calculés, qui appartiennent à la lumière précédente
        faceStamp = nextStamp();
    }

    /**
     * Commence à collecter les dessus de blocs sur lesquels un projecteur pose sa lumière au sol, et note
     * l'empreinte sur laquelle cette lumière décroît
     */
    private static void beginGroundFaces(LightFxEntry entry, float alongX, float alongZ, float length) {
        beginLitFaces(entry, MAX_GROUND_FACES);
        groundMode = true;
        groundAlongX = alongX;
        groundAlongZ = alongZ;
        groundLength = length;
        groundNear = entry.projectionWidthNear;
        groundFar = entry.projectionWidthFar;
        // Où les deux bords de l'empreinte se rejoignent derrière la lampe, borné par le ratio ci-dessus
        groundApex = -groundNear * length / Math.max(groundFar - groundNear, 1e-4f);
        // Et où la lumière commence, un peu devant la lampe, jamais derrière elle
        groundStart = Math.max(0, SPOT_GROUND_START * (length - groundApex) + groundApex);
    }

    /**
     * Étend la lumière sur les surfaces des faces déjà collectées. Les faces ajoutées pendant ce parcours sont
     * reprises par la même boucle, ce qui mène la lumière le long d'un mur et dans ses angles.
     */
    private static void spreadLitFaces(World world, LightFxEntry entry, float baseAlpha) {
        for (int face = 0; face < litFaceCount; face++) {
            expandLitFace(world, entry, face, baseAlpha);
        }
    }

    /**
     * Étend la lumière d'une face aux quatre faces qui prolongent sa surface : la suivante le long d'un mur plat,
     * celle sur laquelle la surface tourne dans un angle, et celle qui nous fait face dans un renfoncement.
     */
    private static void expandLitFace(World world, LightFxEntry entry, int face, float baseAlpha) {
        int x = FACE_BLOCK_X[face], y = FACE_BLOCK_Y[face], z = FACE_BLOCK_Z[face];
        EnumFacing side = EnumFacing.VALUES[FACE_SIDE[face]];
        for (EnumFacing edge : EnumFacing.VALUES) {
            if (edge.getAxis() == side.getAxis()) {
                // On parcourt les quatre arêtes de la face, et pas sa propre direction
                continue;
            }
            int nextX = x + edge.getXOffset(), nextY = y + edge.getYOffset(), nextZ = z + edge.getZOffset();
            if (!catchesLight(world, nextX, nextY, nextZ)) {
                // La surface s'éloigne : la lumière tourne cette arête, sur une face de ce même bloc
                addLitFace(world, entry, x, y, z, edge, baseAlpha);
            } else if (catchesLight(world, nextX + side.getXOffset(), nextY + side.getYOffset(),
                    nextZ + side.getZOffset())) {
                // La surface se rapproche : la lumière continue sur la face qui nous fait face dans l'angle
                addLitFace(world, entry, nextX + side.getXOffset(), nextY + side.getYOffset(),
                        nextZ + side.getZOffset(), edge.getOpposite(), baseAlpha);
            } else {
                // Un mur plat qui continue
                addLitFace(world, entry, nextX, nextY, nextZ, side, baseAlpha);
            }
        }
    }

    /**
     * Ajoute une face d'un bloc à celles sur lesquelles une lumière est posée, s'il y a bien là une face de bloc et
     * si la lumière l'atteint. Une face n'est examinée qu'une fois, prise ou non, ce qui termine le parcours.
     *
     * @return Vrai si la face a été prise, et la lumière s'étend donc à ses voisines
     */
    private static boolean addLitFace(World world, LightFxEntry entry, int x, int y, int z, EnumFacing side,
                                      float baseAlpha) {
        if (litFaceCount >= faceBudget || visitedFaces >= MAX_VISITED_FACES
                || (side == EnumFacing.UP) != groundMode) {
            // Les dessus de blocs appartiennent à la lumière au sol et le reste aux surfaces : chaque passe prend
            // les faces qui sont les siennes. Une tache posée à plat au sol est écartée, trop étirée pour se lire.
            return false;
        }
        int key = faceKey(x, y, z, side.ordinal());
        if (key == 0) {
            // Plus loin de la lampe que sa lumière ne porte
            return false;
        }
        int slot = keySlot(FACE_KEYS, FACE_STAMPS, FACE_KEY_MASK, faceStamp, key);
        if (FACE_STAMPS[slot] == faceStamp) {
            return false;
        }
        FACE_STAMPS[slot] = faceStamp;
        FACE_KEYS[slot] = key;
        visitedFaces++;
        IBlockState state = blockAt(world, x, y, z);
        if (state == null || state.getMaterial() == Material.AIR) {
            return false;
        }
        FACE_POS.setPos(x, y, z);
        AxisAlignedBB box = state.getCollisionBoundingBox(world, FACE_POS);
        if (box == null) {
            // Rien dans quoi un joueur se cognerait, sauf l'eau et la lave, qui reçoivent bien la lumière
            if (!groundMode || !state.getMaterial().isLiquid()) {
                return false;
            }
            box = LIQUID_SURFACE_BOX;
        }
        int front = terrainAt(world, x + side.getXOffset(), y + side.getYOffset(), z + side.getZOffset());
        if (front > 0 && (front & TERRAIN_OPAQUE) != 0) {
            // Enterrée contre un autre bloc : il n'y a pas de face à éclairer ici
            return false;
        }
        // La face est un côté de la boîte du bloc : la lumière tombe sur une dalle là où elle se trouve
        double planeX, planeY, planeZ;
        double alongX = 0, alongY = 0, alongZ = 0, upX = 0, upY = 0, upZ = 0;
        switch (side.getAxis()) {
            case X:
                planeX = x + (side == EnumFacing.EAST ? box.maxX : box.minX);
                planeY = y + box.minY;
                planeZ = z + box.minZ;
                alongZ = box.maxZ - box.minZ;
                upY = box.maxY - box.minY;
                break;
            case Y:
                planeX = x + box.minX;
                planeY = y + (side == EnumFacing.UP ? box.maxY : box.minY);
                planeZ = z + box.minZ;
                alongX = box.maxX - box.minX;
                upZ = box.maxZ - box.minZ;
                break;
            default:
                planeX = x + box.minX;
                planeY = y + box.minY;
                planeZ = z + (side == EnumFacing.SOUTH ? box.maxZ : box.minZ);
                alongX = box.maxX - box.minX;
                upY = box.maxY - box.minY;
                break;
        }
        int at = litFaceCount * 4;
        // Où les quatre coins se trouvent dans le faisceau, d'où le dégradé est lu. Calculé avant la lumière qui
        // les atteint, car une face entièrement au-delà d'un bord du dégradé est écartée sans coûter un rayon.
        int sides = PROFILE_ALL_SIDES;
        for (int corner = 0; corner < 4; corner++) {
            // Les quatre coins de la face, dans l'ordre autour d'elle
            boolean along = corner == 1 || corner == 2;
            boolean up = corner == 2 || corner == 3;
            profileCoords(entry, planeX + (along ? alongX : 0) + (up ? upX : 0),
                    planeY + (along ? alongY : 0) + (up ? upY : 0),
                    planeZ + (along ? alongZ : 0) + (up ? upZ : 0));
            FACE_CORNER_U[at + corner] = profileU;
            FACE_CORNER_V[at + corner] = profileV;
            sides &= profileSide;
        }
        if (sides != 0) {
            // Les quatre coins hors du faisceau du même côté : rien de cette lumière ne tombe dessus, ni au-delà
            return false;
        }
        float brightest = 0;
        for (int corner = 0; corner < 4; corner++) {
            boolean along = corner == 1 || corner == 2;
            boolean up = corner == 2 || corner == 3;
            float light = cornerLight(world, entry, side,
                    planeX + (along ? alongX : 0) + (up ? upX : 0),
                    planeY + (along ? alongY : 0) + (up ? upY : 0),
                    planeZ + (along ? alongZ : 0) + (up ? upZ : 0), baseAlpha);
            FACE_CORNER_LIGHT[at + corner] = light;
            brightest = Math.max(brightest, light);
        }
        if (brightest <= MIN_VISIBLE) {
            // La lumière n'atteint jamais cette face : rien à dessiner dessus, ni à éclairer au-delà
            return false;
        }
        // Gardée comme le plan de la face, pour pouvoir calculer la lumière n'importe où à l'intérieur
        FACE_ORIGIN_X[litFaceCount] = planeX;
        FACE_ORIGIN_Y[litFaceCount] = planeY;
        FACE_ORIGIN_Z[litFaceCount] = planeZ;
        FACE_ALONG_X[litFaceCount] = (float) alongX;
        FACE_ALONG_Y[litFaceCount] = (float) alongY;
        FACE_ALONG_Z[litFaceCount] = (float) alongZ;
        FACE_UP_X[litFaceCount] = (float) upX;
        FACE_UP_Y[litFaceCount] = (float) upY;
        FACE_UP_Z[litFaceCount] = (float) upZ;
        FACE_BLOCK_X[litFaceCount] = x;
        FACE_BLOCK_Y[litFaceCount] = y;
        FACE_BLOCK_Z[litFaceCount] = z;
        FACE_SIDE[litFaceCount] = side.ordinal();
        litFaceCount++;
        return true;
    }

    /** @return La part de la lumière d'une lampe qui atteint un coin d'une face */
    private static float cornerLight(World world, LightFxEntry entry, EnumFacing side, double x, double y, double z,
                                     float baseAlpha) {
        // Jusqu'à quatre faces partagent un coin, et répondre coûte un rayon : gardé le temps d'une lumière
        int key = cornerKey(x, y, z, side.ordinal());
        int slot = key == 0 ? -1 : keySlot(CORNER_KEYS, CORNER_STAMPS, CORNER_KEY_MASK, faceStamp, key);
        if (slot >= 0 && CORNER_STAMPS[slot] == faceStamp) {
            return CORNER_LIGHT[slot];
        }
        float light = computeCornerLight(world, entry, side, x, y, z, baseAlpha);
        if (slot >= 0) {
            CORNER_STAMPS[slot] = faceStamp;
            CORNER_KEYS[slot] = key;
            CORNER_LIGHT[slot] = light;
        }
        return light;
    }

    /**
     * Calcule la lumière reçue par un point d'une face : la part qui y arrive, l'angle sous lequel elle frappe la
     * face, et si la lampe s'y voit
     */
    private static float computeCornerLight(World world, LightFxEntry entry, EnumFacing side,
                                            double x, double y, double z, float baseAlpha) {
        double dx = x - entry.emitX, dy = y - entry.emitY, dz = z - entry.emitZ;
        // La lumière au sol ne garde aucune décroissance aux coins : tout est dans le dégradé, et quels blocs sont
        // dans l'empreinte est répondu à leur prise. Chaque coin d'une route dégagée porte donc le même nombre.
        float light = groundMode ? 1
                : patchRadius > 0 ? patchFalloff(x, y, z) : beamFalloff(entry, dx, dy, dz);
        if (light <= 0) {
            return 0;
        }
        light *= baseAlpha;
        if (light <= MIN_VISIBLE) {
            return 0;
        }
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        float incidence = (float) (-(dx * side.getXOffset() + dy * side.getYOffset() + dz * side.getZOffset())
                / distance);
        if (incidence <= (groundMode ? 0 : 0.05f)) {
            // Face tournée dos à la lampe. Au sol, c'est un bloc dont le dessus est au-dessus d'elle. La lumière
            // rasante n'est pas écartée là : c'est exactement celle qui montre un phare à trente blocs.
            return 0;
        }
        if (!groundMode) {
            // Une lumière qui frappe une surface de face est plus vive qu'une lumière rasante
            light *= incidence;
        }
        // Demandé en dernier, car c'est la seule part qui lit le monde
        if (groundMode) {
            if (Math.abs(y - groundLevel) <= GROUND_LEVEL_TOLERANCE
                    && (float) (dx * groundAlongX + dz * groundAlongZ) < groundTracedFrom) {
                // De niveau avec le sol sous la lampe et plus près que tout obstacle : il n'y a que de l'air entre
                // les deux. C'est tout le cas d'une route dégagée.
                return light * entityVisibility(entry, x, y, z);
            }
            // Un rayon vers le sol y arrive en rasant, et ce qui l'ombre est là où il tombe : moins de tolérance
            return light * visibility(world, entry, x, y, z, GROUND_SHADOW_TOLERANCE);
        }
        return light <= MIN_VISIBLE ? 0
                : light * visibility(world, entry, x, y, z, SURFACE_SHADOW_TOLERANCE);
    }

    /**
     * @return La part de la lumière d'un projecteur qui tombe sur le point visé, dans la mesure où c'est vrai de
     * toute la face dont il est un coin : soit sa distance le long du faisceau. Sa position en travers et en
     * hauteur est laissée au dégradé, qui la porte pour chaque pixel.
     */
    private static float beamFalloff(LightFxEntry entry, double dx, double dy, double dz) {
        // Sa distance le long du faisceau, et son écart à l'axe en travers et en hauteur
        float along = (float) (dx * entry.beamX + dy * entry.beamY + dz * entry.beamZ);
        float t = along / entry.projectionLength;
        if (t <= 0 || t >= 1) {
            return 0;
        }
        float halfWidth = Math.max(SPOT_MIN_SURFACE_SPREAD,
                entry.projectionWidthNear + (entry.projectionWidthFar - entry.projectionWidthNear) * t);
        float halfHeight = Math.max(SPOT_MIN_SURFACE_SPREAD, halfWidth * SPOT_FLATTEN);
        float across = (float) (dx * spotAcrossX + dz * spotAcrossZ);
        float up = (float) (dx * spotUpX + dy * spotUpY + dz * spotUpZ);
        // Au-delà du bloc que le bord du faisceau traverse : ce qui est gardé ici est la marge du dégradé
        if (across <= -halfWidth - PROFILE_MARGIN || across >= halfWidth + PROFILE_MARGIN
                || up <= -halfHeight - PROFILE_MARGIN || up >= halfHeight + PROFILE_MARGIN) {
            return 0;
        }
        return alongFalloff(t);
    }

    /**
     * Calcule quels blocs d'une ligne du terrain l'empreinte d'un faisceau peut atteindre, comme le tronçon
     * {@link DynamXLightFx#rowFromZ} à {@link DynamXLightFx#rowToZ}. Les blocs sous l'empreinte sont parcourus sur
     * le rectangle qui l'entoure, qui en diagonale fait plus du double du sol couvert ; ses extrémités et ses côtés
     * étant droits, la part d'une ligne qui peut tomber dedans est un seul tronçon continu.
     *
     * @return Faux quand l'empreinte n'atteint pas cette ligne de blocs
     */
    private static boolean footprintRow(LightFxEntry entry, int blockX, int minZ, int maxZ) {
        double dx = blockX + 0.5 - entry.emitX;
        rowLow = Double.NEGATIVE_INFINITY;
        rowHigh = Double.POSITIVE_INFINITY;
        // Où un bloc de cette ligne se trouve le long de l'empreinte et en travers, tous deux croissant avec z
        double alongBase = dx * groundAlongX, acrossBase = -dx * groundAlongZ;
        // Entre l'extrémité proche de l'empreinte, où la lumière commence, et son extrémité lointaine
        if (!rowBound(alongBase, groundAlongZ, groundLength + BLOCK_HALF_DIAGONAL)
                || !rowBound(-alongBase, -groundAlongZ, BLOCK_HALF_DIAGONAL - groundStart)) {
            return false;
        }
        // Et entre ses deux côtés, qui s'évasent avec la distance le long d'elle. Élargi de leur pente sur le
        // demi-bloc dont les bornes ci-dessus dépassent chaque extrémité.
        float fan = (groundFar - groundNear) / groundLength;
        double side = groundNear + BLOCK_HALF_DIAGONAL + Math.abs(fan) * BLOCK_HALF_DIAGONAL;
        if (!rowBound(acrossBase - fan * alongBase, groundAlongX - fan * groundAlongZ, side)
                || !rowBound(-acrossBase - fan * alongBase, -groundAlongX - fan * groundAlongZ, side)) {
            return false;
        }
        if (rowLow > rowHigh) {
            return false;
        }
        // Ce sont les milieux des blocs, à un demi-bloc dedans
        rowFromZ = Math.max(minZ, (int) Math.ceil(entry.emitZ + rowLow - 0.5));
        rowToZ = Math.min(maxZ, (int) Math.floor(entry.emitZ + rowHigh - 0.5));
        return rowFromZ <= rowToZ;
    }

    /**
     * Resserre ce tronçon par un côté droit de l'empreinte, écrit {@code base + slope * away < limit}, où away est
     * la distance en z d'un bloc de la ligne à la lampe
     *
     * @return Faux quand aucun bloc de la ligne ne peut être du bon côté
     */
    private static boolean rowBound(double base, double slope, double limit) {
        if (slope > 1e-4) {
            rowHigh = Math.min(rowHigh, (limit - base) / slope);
        } else if (slope < -1e-4) {
            rowLow = Math.max(rowLow, (limit - base) / slope);
        } else if (base >= limit) {
            // Ce côté longe la ligne de blocs, entièrement du mauvais côté
            return false;
        }
        return true;
    }

    /**
     * Dit si une partie d'un bloc du terrain tombe dans l'empreinte du faisceau d'un projecteur. Demandé du seul
     * milieu du bloc, un bloc que le bord traverse serait écarté dès que ce bord passe son milieu, et la lumière
     * finirait sur les côtés des blocs, en escalier ; demandé de tout le bloc, elle finit à l'intérieur du bloc
     * traversé, dont les coins l'éteignent.
     */
    private static boolean touchesFootprint(LightFxEntry entry, int blockX, int blockZ) {
        // L'empreinte élargie de la demi-diagonale d'un bloc, mesurée depuis son milieu : même réponse que tester
        // ses quatre coins pour une fraction du calcul, et l'essentiel du rectangle est résolu ici
        double dx = blockX + 0.5 - entry.emitX, dz = blockZ + 0.5 - entry.emitZ;
        float along = (float) (dx * groundAlongX + dz * groundAlongZ);
        if (along <= groundStart - BLOCK_HALF_DIAGONAL || along >= groundLength + BLOCK_HALF_DIAGONAL) {
            return false;
        }
        float halfWidth = groundNear + (groundFar - groundNear)
                * MathHelper.clamp(along / groundLength, 0f, 1f) + BLOCK_HALF_DIAGONAL;
        float across = (float) (dz * groundAlongX - dx * groundAlongZ);
        return across > -halfWidth && across < halfWidth;
    }

    /**
     * Calcule où un point d'une face se trouve dans le faisceau de la lumière posée, en les deux coordonnées du
     * dégradé : en travers du faisceau sur le premier axe, en hauteur sur le second. Les deux sont lues sur la
     * texture par la carte graphique, pour chaque pixel, ce qui fait tomber le bord d'un faisceau exactement où il
     * tombe, quelle que soit la finesse du dessin et où que soit la caméra. <br>
     * La réponse est laissée dans {@link DynamXLightFx#profileU} et {@link DynamXLightFx#profileV}.
     */
    private static void profileCoords(LightFxEntry entry, double x, double y, double z) {
        if (patchRadius > 0) {
            // La tache d'un faisceau est ronde d'emblée : lue depuis le milieu du dégradé rond
            profileU = 0.5f;
            profileV = 0.5f;
            profileSide = 0;
            return;
        }
        double dx = x - entry.emitX, dy = y - entry.emitY, dz = z - entry.emitZ;
        if (groundMode) {
            // L'écart du point en travers de l'empreinte, en fraction de la largeur de son bout, et sa distance le
            // long d'elle depuis le point de convergence. Les deux varient linéairement sur le sol.
            float across = (float) (dz * groundAlongX - dx * groundAlongZ) / groundFar;
            float along = ((float) (dx * groundAlongX + dz * groundAlongZ) - groundApex)
                    / (groundLength - groundApex);
            profileU = LightFxTextures.PROFILE_CENTER + across * LightFxTextures.PROFILE_SCALE;
            // La lumière au sol n'a pas de hauteur : sa seconde direction est la distance le long du faisceau
            profileV = LightFxTextures.PROFILE_ALONG_OFFSET + along * LightFxTextures.PROFILE_ALONG_SCALE;
            // Le bord du faisceau est là où le point est aussi loin en travers qu'il l'est le long : il s'évase
            profileSide = 0;
            if (across >= along) {
                profileSide |= PROFILE_PAST_HIGH;
            }
            if (across <= -along) {
                profileSide |= PROFILE_PAST_LOW;
            }
            if (along <= 0) {
                profileSide |= PROFILE_BEFORE_START;
            }
            if (along >= 1) {
                profileSide |= PROFILE_PAST_END;
            }
            return;
        }
        float t = (float) (dx * entry.beamX + dy * entry.beamY + dz * entry.beamZ) / entry.projectionLength;
        float halfWidth = Math.max(SPOT_MIN_SURFACE_SPREAD, entry.projectionWidthNear
                + (entry.projectionWidthFar - entry.projectionWidthNear) * t);
        float halfHeight = Math.max(SPOT_MIN_SURFACE_SPREAD, halfWidth * SPOT_FLATTEN);
            // Les deux directions décroissent séparément plutôt que réunies en une distance à l'axe : un faisceau
            // rond ne laisserait sur un mur qu'une tache d'un bloc, pour plusieurs blocs de route devant
        float across = (float) (dx * spotAcrossX + dz * spotAcrossZ) / halfWidth;
        float up = (float) (dx * spotUpX + dy * spotUpY + dz * spotUpZ) / halfHeight;
        profileU = LightFxTextures.PROFILE_CENTER + across * LightFxTextures.PROFILE_SCALE;
        profileV = LightFxTextures.PROFILE_CENTER + up * LightFxTextures.PROFILE_SCALE;
        profileSide = 0;
        if (across >= 1) {
            profileSide |= PROFILE_PAST_HIGH;
        }
        if (across <= -1) {
            profileSide |= PROFILE_PAST_LOW;
        }
        if (up >= 1) {
            profileSide |= PROFILE_PAST_TOP;
        }
        if (up <= -1) {
            profileSide |= PROFILE_PAST_BOTTOM;
        }
        if (t <= 0) {
            profileSide |= PROFILE_BEFORE_START;
        }
        if (t >= 1) {
            profileSide |= PROFILE_PAST_END;
        }
    }

    /**
     * @return La part de la lumière de la tache qui atteint le point donné : un milieu large et uniforme et un bord
     * doux, le dégradé d'un sprite rond
     */
    private static float patchFalloff(double x, double y, double z) {
        double dx = x - patchX, dy = y - patchY, dz = z - patchZ;
        float edge = (float) ((1 - Math.sqrt(dx * dx + dy * dy + dz * dz) / patchRadius) * 2);
        if (edge <= 0) {
            return 0;
        }
        return edge >= 1 ? 1 : edge * edge;
    }

    /**
     * Dessine la lumière posée sur une face, en une grille de sous-quads dont la lumière est calculée à chaque
     * noeud. Dessinée en entier, avec sa lumière connue à ses seuls coins, une face porte un dégradé plat sur tout
     * le bloc, et un bord d'ombre ou de faisceau tombe sur ses coins ; découpée, ils tombent à l'intérieur. <br>
     * La finesse est décidée face par face, d'après sa distance à la caméra et l'uniformité de sa lumière : une
     * face dont les quatre coins s'accordent est dessinée d'un bloc, ce qui est l'essentiel d'une route. <br>
     * Les noeuds des bords sont partagés avec les faces voisines, comme ses coins : la lumière passe sans couture.
     */
    private static void drawLitFace(BufferBuilder buffer, World world, LightFxEntry entry, int face,
                                    float baseAlpha) {
        int at = face * 4;
        float light00 = FACE_CORNER_LIGHT[at], light10 = FACE_CORNER_LIGHT[at + 1],
                light11 = FACE_CORNER_LIGHT[at + 2], light01 = FACE_CORNER_LIGHT[at + 3];
        EnumFacing side = EnumFacing.VALUES[FACE_SIDE[face]];
        // Décollée de la face sur laquelle elle repose, pour garder la lumière hors de la surface elle-même
        double originX = FACE_ORIGIN_X[face] + side.getXOffset() * SURFACE_OFFSET;
        double originY = FACE_ORIGIN_Y[face] + side.getYOffset() * SURFACE_OFFSET;
        double originZ = FACE_ORIGIN_Z[face] + side.getZOffset() * SURFACE_OFFSET;
        float alongX = FACE_ALONG_X[face], alongY = FACE_ALONG_Y[face], alongZ = FACE_ALONG_Z[face];
        float upX = FACE_UP_X[face], upY = FACE_UP_Y[face], upZ = FACE_UP_Z[face];
        int steps = faceSteps(face, light00, light10, light11, light01);
        if (steps > 1) {
            frameFaceSamplesLeft -= (steps + 1) * (steps + 1);
        }
        // La lumière à chaque noeud, calculée une fois puis utilisée par les sous-quads qui s'y rejoignent, avec
        // la place du noeud dans le faisceau. Les quatre coins sont ceux déjà connus, partagés avec les voisines.
        int row = steps + 1;
        for (int up = 0; up <= steps; up++) {
            boolean upEdge = up == 0 || up == steps;
            float v = (float) up / steps;
            for (int along = 0; along <= steps; along++) {
                int node = up * row + along;
                if (upEdge && (along == 0 || along == steps)) {
                    // Un coin de la face : déjà calculé à sa prise et partagé avec les faces voisines
                    int corner = along == 0 ? (up == 0 ? 0 : 3) : (up == 0 ? 1 : 2);
                    FACE_GRID[node] = FACE_CORNER_LIGHT[at + corner];
                    FACE_GRID_U[node] = FACE_CORNER_U[at + corner];
                    FACE_GRID_V[node] = FACE_CORNER_V[at + corner];
                    continue;
                }
                float u = (float) along / steps;
                double nodeX = FACE_ORIGIN_X[face] + alongX * u + upX * v;
                double nodeY = FACE_ORIGIN_Y[face] + alongY * u + upY * v;
                double nodeZ = FACE_ORIGIN_Z[face] + alongZ * u + upZ * v;
                FACE_GRID[node] = cornerLight(world, entry, side, nodeX, nodeY, nodeZ, baseAlpha);
                profileCoords(entry, nodeX, nodeY, nodeZ);
                FACE_GRID_U[node] = profileU;
                FACE_GRID_V[node] = profileV;
            }
        }
        float stepSize = 1f / steps;
        for (int up = 0; up < steps; up++) {
            for (int along = 0; along < steps; along++) {
                // Les quatre noeuds de ce sous-quad, qui portent sa lumière et sa place dans le faisceau
                int node00 = up * row + along, node10 = node00 + 1, node01 = node00 + row, node11 = node01 + 1;
                float lit00 = FACE_GRID[node00], lit10 = FACE_GRID[node10],
                        lit01 = FACE_GRID[node01], lit11 = FACE_GRID[node11];
                if (lit00 <= 0 && lit10 <= 0 && lit01 <= 0 && lit11 <= 0) {
                    // Entièrement dans le noir : ombre d'un véhicule, sol à côté du faisceau, mur non atteint
                    continue;
                }
                float u0 = along * stepSize, u1 = u0 + stepSize;
                float v0 = up * stepSize, v1 = v0 + stepSize;
                faceVertex(buffer, entry, originX, originY, originZ, alongX, alongY, alongZ, upX, upY, upZ,
                        u0, v0, node00, lit00);
                faceVertex(buffer, entry, originX, originY, originZ, alongX, alongY, alongZ, upX, upY, upZ,
                        u1, v0, node10, lit10);
                faceVertex(buffer, entry, originX, originY, originZ, alongX, alongY, alongZ, upX, upY, upZ,
                        u1, v1, node11, lit11);
                faceVertex(buffer, entry, originX, originY, originZ, alongX, alongY, alongZ, upX, upY, upZ,
                        u0, v1, node01, lit01);
            }
        }
    }

    /**
     * Finesse de découpe d'une face pour dessiner la lumière posée dessus, en sous-quads par axe
     *
     * @return Entre 1, soit toute la face en un seul quad, et {@link DynamXLightFx#MAX_FACE_STEPS}
     */
    private static int faceSteps(int face, float light00, float light10, float light11, float light01) {
        // Ce que le joueur a demandé, soit toute la face en un quad au réglage le plus bas
        int finest = MAX_FACE_STEPS;
        if (finest <= 1 || frameFaceSamplesLeft <= 0) {
            return 1;
        }
        // Abandonnée cran par cran sur la fin des points de lumière de l'image : les faces sont posées dans
        // l'ordre des blocs, et un budget qui s'arrête net tranche une ligne visible sur la route
        int taper = (int) (frameFaceSampleBudget * FACE_SAMPLE_TAPER);
        if (taper > 0 && frameFaceSamplesLeft < taper) {
            finest = Math.min(finest, 1 + MAX_DETAIL * frameFaceSamplesLeft / taper);
            if (finest <= 1) {
                return 1;
            }
        }
        float brightest = Math.max(Math.max(light00, light10), Math.max(light11, light01));
        float dimmest = Math.min(Math.min(light00, light10), Math.min(light11, light01));
        if (brightest <= 0 || dimmest >= brightest * (1 - FACE_FLAT_TOLERANCE)) {
            // Lumière déjà uniforme sur cette face : la découper paierait des rayons pour le même dégradé
            return 1;
        }
        // Mesurée jusqu'au milieu de la face : la part d'écran qu'elle couvre décide si la découpe se voit
        double dx = FACE_ORIGIN_X[face] + FACE_ALONG_X[face] * 0.5f + FACE_UP_X[face] * 0.5f - cameraX;
        double dy = FACE_ORIGIN_Y[face] + FACE_ALONG_Y[face] * 0.5f + FACE_UP_Y[face] * 0.5f - cameraY;
        double dz = FACE_ORIGIN_Z[face] + FACE_ALONG_Z[face] * 0.5f + FACE_UP_Z[face] * 0.5f - cameraZ;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return MathHelper.clamp(finest - (int) (distance * frameFaceStepFactor), 1, finest);
    }

    /**
     * Met un noeud de la grille d'une face dans le buffer, placé sur le plan de cette face et texturé avec le
     * dégradé du faisceau à ce noeud : entre les noeuds, la carte graphique le lit pour chaque pixel.
     *
     * @param node Quel noeud de la grille c'est, là où ses coordonnées de dégradé ont été gardées
     */
    private static void faceVertex(BufferBuilder buffer, LightFxEntry entry, double originX, double originY,
                                   double originZ, float alongX, float alongY, float alongZ, float upX, float upY,
                                   float upZ, float u, float v, int node, float light) {
        texturedVertex(buffer, originX + alongX * u + upX * v, originY + alongY * u + upY * v,
                originZ + alongZ * u + upZ * v, FACE_GRID_U[node], FACE_GRID_V[node], entry, light);
    }

    /**
     * @param side La face du bloc, en {@link EnumFacing#ordinal()}
     * @return Une clé représentant une face d'un bloc autour de la lampe, ou 0 pour un bloc trop loin. Toute la
     * position est pliée dans un seul nombre, ce qui permet de garder les faces vues dans une simple table.
     */
    private static int faceKey(int x, int y, int z, int side) {
        int dx = x - keyBaseX + 64, dy = y - keyBaseY + 64, dz = z - keyBaseZ + 64;
        if ((dx | dy | dz) < 0 || dx > 127 || dy > 127 || dz > 127) {
            return 0;
        }
        return 1 + ((((dx << 7 | dy) << 7 | dz) << 3) | side);
    }

    /**
     * @return Une clé représentant un coin d'une face, de même, arrondie aux demi-blocs où tombent les coins des
     * boîtes des blocs, ou 0 pour un coin entre eux ou trop loin
     */
    private static int cornerKey(double x, double y, double z, int side) {
        double halvesX = (x - keyBaseX) * 2, halvesY = (y - keyBaseY) * 2, halvesZ = (z - keyBaseZ) * 2;
        int qx = (int) Math.floor(halvesX + 0.5), qy = (int) Math.floor(halvesY + 0.5),
                qz = (int) Math.floor(halvesZ + 0.5);
        if (Math.abs(halvesX - qx) > 1e-3 || Math.abs(halvesY - qy) > 1e-3 || Math.abs(halvesZ - qz) > 1e-3) {
            // Coin d'un bloc de forme inhabituelle : à calculer, pas à garder
            return 0;
        }
        qx += 128;
        qy += 128;
        qz += 128;
        if ((qx | qy | qz) < 0 || qx > 255 || qy > 255 || qz > 255) {
            return 0;
        }
        return 1 + ((((qx << 8 | qy) << 8 | qz) << 3) | side);
    }

    /**
     * Trouve la case d'une clé dans une des tables de clés. Elles sont à adressage ouvert : une clé occupe la
     * première case libre à partir de son hash, et une case est libre quand sa dernière marque n'est pas celle
     * demandée, si bien que vider une table est une incrémentation de marque. Elles sont dimensionnées pour ne
     * jamais se remplir, voir {@link DynamXLightFx#MAX_VISITED_FACES}.
     *
     * @return La case tenant cette clé, ou la case libre où l'écrire
     */
    private static int keySlot(int[] keys, int[] stamps, int mask, int stamp, int key) {
        int slot = key * 0x9E3779B1 >>> 11 & mask;
        while (stamps[slot] == stamp && keys[slot] != key) {
            slot = slot + 1 & mask;
        }
        return slot;
    }

    /**
     * Jette tout ce que le renderer sait du terrain. Appelé de lui-même toutes les
     * {@link DynamXLightFx#TERRAIN_GENERATION_FRAMES} images, et public pour ce qui change vraiment le monde.
     */
    public static void invalidateTerrain() {
        groundStamp = nextStamp();
        terrainGenerationAge = 0;
    }

    /**
     * @return Une marque avec laquelle aucune table n'a encore été écrite, et qui vide donc celles qui l'utilisent.
     * Jamais 0, valeur que les tables portent avant toute écriture.
     */
    private static int nextStamp() {
        tableStamp = tableStamp == -1 ? 1 : tableStamp + 1;
        return tableStamp;
    }

    /**
     * @param t La distance le long du faisceau, 0 à la lampe et 1 au bout de la projection
     * @return La part de lumière qui arrive aussi loin. Ce qui en tombe sur un point en travers est dans le dégradé.
     */
    static float alongFalloff(float t) {
        // La lumière monte juste devant la lampe puis décroît jusqu'à rien, pour que la projection finisse sur
        // l'horizon. L'exposant est sous deux exprès : une longue traîne faible plutôt qu'un bord net.
        float fadeOut = 1 - t;
        return Math.min(1, t / SPOT_NEAR_RAMP) * fadeOut * MathHelper.sqrt(fadeOut);
    }

    /**
     * La même chose pour la lumière posée au sol, qui commence devant le véhicule et non sous lui : un phare
     * éclaire la route devant, le sol sous la lampe étant entre les roues avant.
     *
     * @param t La distance le long de l'empreinte, mesurée comme le dégradé la mesure : 0 au point de convergence
     *          des bords du faisceau, derrière la lampe, et 1 au bout de sa portée
     */
    static float groundAlongFalloff(float t) {
        float fadeIn = (t - SPOT_GROUND_START) / SPOT_NEAR_RAMP;
        if (fadeIn <= 0) {
            // Encore derrière la carrosserie du véhicule : la route n'est éclairée qu'à partir des lampes
            return 0;
        }
        float fadeOut = 1 - t;
        return fadeOut <= 0 ? 0 : Math.min(1, fadeIn) * fadeOut * MathHelper.sqrt(fadeOut);
    }

    /**
     * Calcule la direction, la longueur et le point d'atterrissage de chaque faisceau. Fait une fois par image, les
     * faisceaux servant à deux passes : les taches sur les murs et les cônes dans l'air.
     */
    private static void resolveBeams(World world, LightFxEntry entry) {
        entry.resolvedBeams = 0;
        float maxLength = Math.min(entry.radius * 1.5f, MAX_BEAM_LENGTH);
        // Un rayon par faisceau. Assez loin, où ni sa fin ni sa tache ne se distinguent, aucun n'est lancé.
        boolean traced = entry.cameraDistanceSq < frameBeamTraceDistanceSq;
        for (int beam = 0; beam < entry.beamCount; beam++) {
            // Répartit les faisceaux autour de l'axe vertical de la lumière
            float spread = (float) (beam * 2 * Math.PI / entry.beamCount);
            float cos = MathHelper.cos(spread);
            float sin = MathHelper.sin(spread);
            float dirX = entry.beamX * cos - entry.beamZ * sin;
            float dirY = entry.beamY;
            float dirZ = entry.beamX * sin + entry.beamZ * cos;

            // Arrête le faisceau sur le premier obstacle, mur comme véhicule ou joueur
            float length = maxLength;
            if (traced) {
                length *= traceLight(world, entry, entry.emitX, entry.emitY, entry.emitZ,
                        entry.emitX + dirX * maxLength, entry.emitY + dirY * maxLength,
                        entry.emitZ + dirZ * maxLength);
            } else {
                traceFace = null;
            }
            if (length < 0.3f) {
                continue;
            }
            int index = entry.resolvedBeams++;
            entry.beamDirX[index] = dirX;
            entry.beamDirY[index] = dirY;
            entry.beamDirZ[index] = dirZ;
            entry.beamLength[index] = length;
            // Un faisceau arrêté par un véhicule ou un joueur n'atterrit sur rien : il est coupé là, sans tache
            entry.beamHitFace[index] = traceEntity ? null : traceFace;
            if (entry.beamHitFace[index] != null) {
                entry.beamHitX[index] = traceX;
                entry.beamHitY[index] = traceY;
                entry.beamHitZ[index] = traceZ;
                // Le bloc de cette face : la tache dessinée là où le faisceau atterrit grandit depuis lui
                entry.beamHitBlockX[index] = blockX;
                entry.beamHitBlockY[index] = blockY;
                entry.beamHitBlockZ[index] = blockZ;
            }
        }
    }

    /** Dessine les cônes des faisceaux d'une lumière, que seul un gyrophare balayant possède */
    private static void renderBeamCones(BufferBuilder buffer, LightFxEntry entry) {
        // La caméra à l'intérieur de la section la plus vive d'un cône voilerait toute la vue
        float alpha = CONE_ALPHA * entry.brightness
                * nearFade(Math.sqrt(entry.cameraDistanceSq), BEAM_FADE_END, BEAM_FADE_START);
        if (alpha <= MIN_VISIBLE) {
            return;
        }
        for (int beam = 0; beam < entry.resolvedBeams; beam++) {
            renderBeamCone(buffer, entry, entry.beamDirX[beam], entry.beamDirY[beam], entry.beamDirZ[beam],
                    entry.beamLength[beam], alpha);
        }
    }

    /**
     * Dessine les taches que les faisceaux d'un gyrophare posent sur les murs : les projecteurs n'émettent aucun
     * faisceau, voir {@link DynamXLightFx#render}
     */
    private static void renderBeamSpots(BufferBuilder buffer, World world, LightFxEntry entry) {
        for (int beam = 0; beam < entry.resolvedBeams && beamPatchesLeft > 0; beam++) {
            // Un faisceau atterrissant à plat sur le sol éclaire une tache trop étirée pour se lire
            if (entry.beamHitFace[beam] != null && entry.beamHitFace[beam] != EnumFacing.UP) {
                beamPatchesLeft--;
                renderBeamPatch(buffer, world, entry, beam);
            }
        }
    }

    /** Dessine un faisceau, en cône autour de sa direction */
    private static void renderBeamCone(BufferBuilder buffer, LightFxEntry entry, float dirX, float dirY, float dirZ,
                                       float length, float alpha) {
        // Axe horizontal de la section du faisceau
        float sideX = dirZ, sideZ = -dirX;
        float sideLength = MathHelper.sqrt(sideX * sideX + sideZ * sideZ);
        if (sideLength < 0.001f) {
            // Le faisceau vise droit en haut ou en bas
            sideX = 1;
            sideZ = 0;
        } else {
            sideX /= sideLength;
            sideZ /= sideLength;
        }
        // Axe vertical de la section, perpendiculaire aux deux autres
        float sectionUpX = -sideZ * dirY;
        float sectionUpY = sideZ * dirX - sideX * dirZ;
        float sectionUpZ = sideX * dirY;
        float sectionUpLength = MathHelper.sqrt(sectionUpX * sectionUpX + sectionUpY * sectionUpY + sectionUpZ * sectionUpZ);
        if (sectionUpLength < 0.001f) {
            return;
        }
        sectionUpX /= sectionUpLength;
        sectionUpY /= sectionUpLength;
        sectionUpZ /= sectionUpLength;

        // Demi-largeur et demi-hauteur du cône, à la lampe et au bout : étroit et haut, comme un vrai miroir
        float nearH = 0, farH = 0.06f + length * 0.045f;
        float nearV = 0, farV = 0.1f + length * 0.14f;

        // Un faisceau est un mince trait de lumière : vu de l'autre côté d'une rue il fait quelques pixels, et les
        // côtés de sa section ne se distinguent pas. Une rue de véhicules d'urgence en dessine des centaines.
        int segments = Math.max(SEGMENTS / 2,
                SEGMENTS - (int) (Math.sqrt(entry.cameraDistanceSq) * frameConeSegmentFactor));
        for (int band = 0; band < BEAM_STEPS.length - 1; band++) {
            float stepA = BEAM_STEPS[band], stepB = BEAM_STEPS[band + 1];
            float alphaA = alpha * BEAM_STEP_ALPHAS[band], alphaB = alpha * BEAM_STEP_ALPHAS[band + 1];
            for (int segment = 0; segment < segments; segment++) {
                float angleA = (float) (segment * 2 * Math.PI / segments);
                float angleB = (float) ((segment + 1) * 2 * Math.PI / segments);
                coneVertex(buffer, entry, dirX, dirY, dirZ, sideX, sideZ, sectionUpX, sectionUpY, sectionUpZ,
                        length, nearH, farH, nearV, farV, stepA, alphaA, angleA);
                coneVertex(buffer, entry, dirX, dirY, dirZ, sideX, sideZ, sectionUpX, sectionUpY, sectionUpZ,
                        length, nearH, farH, nearV, farV, stepA, alphaA, angleB);
                coneVertex(buffer, entry, dirX, dirY, dirZ, sideX, sideZ, sectionUpX, sectionUpY, sectionUpZ,
                        length, nearH, farH, nearV, farV, stepB, alphaB, angleB);
                coneVertex(buffer, entry, dirX, dirY, dirZ, sideX, sideZ, sectionUpX, sectionUpY, sectionUpZ,
                        length, nearH, farH, nearV, farV, stepB, alphaB, angleA);
            }
        }
    }

    /** Met un sommet d'un cône de faisceau dans le buffer */
    private static void coneVertex(BufferBuilder buffer, LightFxEntry entry, float dirX, float dirY, float dirZ,
                                   float sideX, float sideZ, float upX, float upY, float upZ,
                                   float length, float nearH, float farH, float nearV, float farV,
                                   float step, float alpha, float angle) {
        // La section du cône s'élargit de la lampe au bout du faisceau
        float sectionH = MathHelper.cos(angle) * (nearH + (farH - nearH) * step);
        float sectionV = MathHelper.sin(angle) * (nearV + (farV - nearV) * step);
        // Mesuré depuis la face avant de la lampe : le faisceau sort du verre du phare
        double x = entry.emitX + dirX * length * step + sideX * sectionH + upX * sectionV;
        double y = entry.emitY + dirY * length * step + upY * sectionV;
        double z = entry.emitZ + dirZ * length * step + sideZ * sectionH + upZ * sectionV;
        vertex(buffer, x, y, z, entry, alpha);
    }

    /* Marqueurs de débogage */

    /**
     * Dessine, autour de chaque lampe de l'image, la boîte que le renderer a comprise comme étant cette lampe et la
     * direction dans laquelle sa lumière la quitte : la boîte épouse le verre de la lampe sur le modèle quand la
     * lumière a été lue correctement, ce qui distingue une lampe mal placée d'un effet mal dessiné. Dessinée à
     * travers le monde exprès. Seulement tant que <code>/client_dynamx lightfx debug</code> est actif.
     */
    private static void renderLampMarkers(int rendered) {
        GlStateManager.disableTexture2D();
        GlStateManager.disableDepth();
        BufferBuilder buffer = Tessellator.getInstance().getBuffer();
        buffer.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
        for (int i = 0; i < rendered; i++) {
            LightFxEntry entry = ENTRIES.get(i);
            // La taille mesurée du verre de la lampe, sur laquelle son halo est dimensionné
            float half = Math.max(entry.lampHalfWidth, 0.02f);
            markerBox(buffer, entry.x, entry.y, entry.z, half, half, half, 1, 1, 0);
            // Sa longueur mesurée, soit la ligne sur laquelle sa rangée de halos est étalée
            markerLine(buffer, entry.x - entry.glowSpanX, entry.y - entry.glowSpanY, entry.z - entry.glowSpanZ,
                    entry.x + entry.glowSpanX, entry.y + entry.glowSpanY, entry.z + entry.glowSpanZ, 1, 0, 1);
            // Et où va sa lumière : le long du faisceau d'un projecteur, autour de l'axe d'un gyrophare
            markerLine(buffer, entry.emitX, entry.emitY, entry.emitZ,
                    entry.emitX + entry.beamX, entry.emitY + entry.beamY, entry.emitZ + entry.beamZ, 0, 1, 1);
        }
        Tessellator.getInstance().draw();
        // Tous deux remis par restoreRender, qui suit et connaît l'état trouvé au début de l'image
        GlStateManager.enableTexture2D();
    }

    /** Dessine les douze arêtes d'une boîte centrée sur la position donnée */
    private static void markerBox(BufferBuilder buffer, double x, double y, double z,
                                  float halfX, float halfY, float halfZ, float red, float green, float blue) {
        double minX = x - halfX, minY = y - halfY, minZ = z - halfZ;
        double maxX = x + halfX, maxY = y + halfY, maxZ = z + halfZ;
            // Les quatre arêtes de chaque axe tour à tour
        markerLine(buffer, minX, minY, minZ, maxX, minY, minZ, red, green, blue);
        markerLine(buffer, minX, maxY, minZ, maxX, maxY, minZ, red, green, blue);
        markerLine(buffer, minX, minY, maxZ, maxX, minY, maxZ, red, green, blue);
        markerLine(buffer, minX, maxY, maxZ, maxX, maxY, maxZ, red, green, blue);

        markerLine(buffer, minX, minY, minZ, minX, maxY, minZ, red, green, blue);
        markerLine(buffer, maxX, minY, minZ, maxX, maxY, minZ, red, green, blue);
        markerLine(buffer, minX, minY, maxZ, minX, maxY, maxZ, red, green, blue);
        markerLine(buffer, maxX, minY, maxZ, maxX, maxY, maxZ, red, green, blue);

        markerLine(buffer, minX, minY, minZ, minX, minY, maxZ, red, green, blue);
        markerLine(buffer, maxX, minY, minZ, maxX, minY, maxZ, red, green, blue);
        markerLine(buffer, minX, maxY, minZ, minX, maxY, maxZ, red, green, blue);
        markerLine(buffer, maxX, maxY, minZ, maxX, maxY, maxZ, red, green, blue);
    }

    private static void markerLine(BufferBuilder buffer, double fromX, double fromY, double fromZ,
                                   double toX, double toY, double toZ, float red, float green, float blue) {
        buffer.pos(fromX - originX, fromY - originY, fromZ - originZ).color(red, green, blue, 1).endVertex();
        buffer.pos(toX - originX, toY - originY, toZ - originZ).color(red, green, blue, 1).endVertex();
    }

    /* Aides au dessin */

    /** Dessine un quad face à la caméra, texturé avec toute la texture liée */
    private static void billboard(BufferBuilder buffer, LightFxEntry entry, double x, double y, double z,
                                  float size, float alpha) {
        billboard(buffer, entry, x, y, z, size, alpha, entry.red, entry.green, entry.blue);
    }

    /** Dessine un billboard dans sa propre couleur, celle de ce tronçon de lampe et non de toute la lumière */
    private static void billboard(BufferBuilder buffer, LightFxEntry entry, double x, double y, double z,
                                  float size, float alpha, float red, float green, float blue) {
        quad(buffer, entry, x, y, z, rightX * size, rightY * size, rightZ * size,
                upX * size, upY * size, upZ * size, alpha, red, green, blue);
    }

    /** Dessine un quad centré sur la position donnée et tendu sur les deux demi-axes donnés */
    private static void quad(BufferBuilder buffer, LightFxEntry entry, double x, double y, double z,
                             float aX, float aY, float aZ, float bX, float bY, float bZ, float alpha) {
        quad(buffer, entry, x, y, z, aX, aY, aZ, bX, bY, bZ, alpha, entry.red, entry.green, entry.blue);
    }

    private static void quad(BufferBuilder buffer, LightFxEntry entry, double x, double y, double z,
                             float aX, float aY, float aZ, float bX, float bY, float bZ, float alpha,
                             float red, float green, float blue) {
        texturedVertex(buffer, x - aX - bX, y - aY - bY, z - aZ - bZ, 0, 0, alpha, red, green, blue);
        texturedVertex(buffer, x - aX + bX, y - aY + bY, z - aZ + bZ, 0, 1, alpha, red, green, blue);
        texturedVertex(buffer, x + aX + bX, y + aY + bY, z + aZ + bZ, 1, 1, alpha, red, green, blue);
        texturedVertex(buffer, x + aX - bX, y + aY - bY, z + aZ - bZ, 1, 0, alpha, red, green, blue);
    }

    /** Met un sommet non texturé dans le buffer, en utilisant le centre opaque de la texture liée */
    private static void vertex(BufferBuilder buffer, double x, double y, double z, LightFxEntry entry, float alpha) {
        texturedVertex(buffer, x, y, z, 0.5f, 0.5f, entry, alpha);
    }

    private static void texturedVertex(BufferBuilder buffer, double x, double y, double z, float u, float v,
                                       LightFxEntry entry, float alpha) {
        texturedVertex(buffer, x, y, z, u, v, alpha, entry.red, entry.green, entry.blue);
    }

    private static void texturedVertex(BufferBuilder buffer, double x, double y, double z, float u, float v,
                                       float alpha, float red, float green, float blue) {
        alpha = MathHelper.clamp(alpha, 0f, 1f);
        // Les sommets sont relatifs au centre du rendu, soit l'entité de vue et non la caméra
        buffer.pos(x - originX, y - originY, z - originZ).tex(u, v).color(red, green, blue, alpha).endVertex();
    }

    /* Calculs de lumière */

    /**
     * Dit si le soleil est assez haut pour que les lumières ne montrent que le halo de leur lampe : ce qu'elles
     * posent sur le monde et les faisceaux ne sont alors pas calculés du tout, et c'est là tout leur coût.
     * Seulement dans une dimension qui a un ciel. Seule l'heure compte, donc une lampe dans un tunnel à midi ne
     * garde aussi que son halo.
     */
    private static boolean glowsOnly(World world) {
        if (!world.provider.hasSkyLight()) {
            return false;
        }
        // L'heure du jour, prise par le modulo positif pour qu'un temps remis en arrière se lise encore
        long dayTime = Math.floorMod(world.getWorldTime(), 24000L);
        return dayTime >= HIGH_SUN_FROM && dayTime < HIGH_SUN_TO;
    }

    /**
     * Calcule la luminosité finale d'une lumière, d'après son intensité propre, l'intensité configurée, la distance
     * à la caméra et la lumière ambiante : comme une vraie, une lumière se remarque bien plus dans le noir. Un
     * projecteur suit la lumière du jour bien plus que les autres, ce qu'il pose sur la route étant le seul effet
     * que le soleil noie vraiment.
     */
    private static float computeBrightness(World world, LightFxEntry entry) {
        float brightness = entry.intensity * DynamXConfig.lightFxIntensity;

        // Atténue les lumières les plus lointaines, au lieu de les faire apparaître et disparaître
        double distance = Math.sqrt(entry.cameraDistanceSq);
        double maxDistance = DynamXConfig.lightFxMaxDistance;
        if (distance > maxDistance * 0.7) {
            brightness *= (float) MathHelper.clamp(1 - (distance - maxDistance * 0.7) / (maxDistance * 0.3), 0, 1);
        }

        BlockPos.MutableBlockPos pos = GROUND_POS;
        pos.setPos(entry.x, entry.y, entry.z);
        float darkness = 1;
        // Lue sur le chunk de la lampe : les lampes d'un véhicule sont dans le même, et une rampe en fait six
        Chunk chunk = pos.getY() < 0 || pos.getY() > 255 ? null : chunkAt(world, pos.getX(), pos.getZ());
        if (chunk != null) {
            // La part de lumière du jour qui atteint la lumière : 0 dans un tunnel, 1 dehors à midi
            float sky = chunk.getLightFor(EnumSkyBlock.SKY, pos) / 15f;
            darkness = 1 - sky * frameSunBrightness;
        }
        // 0 sous le soleil de midi, 1 la nuit et sous un toit à toute heure
        float unlit = MathHelper.clamp(darkness * 1.25f, 0f, 1f);
        if (entry.spot) {
            unlit = (float) Math.pow(unlit, SPOT_DARKNESS_CURVE);
            return brightness * (SPOT_DAYLIGHT_FLOOR + (1 - SPOT_DAYLIGHT_FLOOR) * unlit);
        }
        return brightness * (DAYLIGHT_FLOOR + (1 - DAYLIGHT_FLOOR) * unlit);
    }

    /**
     * Atténue un effet quand la caméra approche de sa lampe : un effet additif dessiné sur la caméra couvrirait
     * tout l'écran.
     *
     * @param end   La distance en dessous de laquelle l'effet n'est pas dessiné
     * @param start La distance au-dessus de laquelle il est dessiné à pleine force
     */
    private static float nearFade(double distance, float end, float start) {
        return (float) MathHelper.clamp((distance - end) / (start - end), 0, 1);
    }

    /**
     * @param angleDelta L'angle entre une direction et le premier faisceau, en radians
     * @param spread     La largeur du lobe d'un faisceau, en radians
     * @return La part dont les faisceaux de la lumière éclairent cette direction, entre 0 et 1
     */
    private static float beamWeight(LightFxEntry entry, float angleDelta, float spread) {
        float step = (float) (2 * Math.PI / Math.max(1, entry.beamCount));
        // Replie l'angle sur le faisceau le plus proche
        float folded = angleDelta % step;
        if (folded > step / 2) {
            folded -= step;
        } else if (folded < -step / 2) {
            folded += step;
        }
        return (float) Math.exp(-(folded * folded) / (2 * spread * spread));
    }

    /**
     * Trouve la surface sous une colonne de blocs, dans la table de celles déjà trouvées. La recherche porte
     * toujours sur tout {@link DynamXLightFx#GROUND_MAX_DROP}, pour que sa réponse serve à toutes les lumières :
     * une lumière qui porte moins loin écarte simplement une surface trop profonde.
     *
     * @param fromY   Le bloc où la recherche commence, vers le bas
     * @param maxDrop La profondeur jusqu'à laquelle cette lumière accepte une surface
     */
    private static float groundBelow(World world, int blockX, int blockZ, int fromY, int maxDrop) {
        int key = groundKey(blockX, blockZ, fromY);
        if (key == 0) {
            return findGroundBelow(world, blockX, blockZ, fromY, maxDrop);
        }
        int slot = keySlot(GROUND_KEYS, GROUND_STAMPS, GROUND_KEY_MASK, groundStamp, key);
        float height;
        frameGroundLookups++;
        if (GROUND_STAMPS[slot] == groundStamp) {
            frameGroundHits++;
            height = GROUND_HEIGHTS[slot];
        } else {
            height = findGroundBelow(world, blockX, blockZ, fromY, GROUND_MAX_DROP);
            GROUND_STAMPS[slot] = groundStamp;
            GROUND_KEYS[slot] = key;
            GROUND_HEIGHTS[slot] = height;
        }
        // Plus profond que ne porte cette lumière : un trou où elle ne brille pas, de son point de vue
        return height < fromY - maxDrop ? Float.NaN : height;
    }

    /**
     * @return La clé d'une colonne et d'une hauteur de départ dans cette table, ou 0 pour une qu'elle ne peut pas
     * tenir. Les coordonnées sont réduites à mille blocs, bien au-delà de ce qu'une image lit.
     */
    private static int groundKey(int blockX, int blockZ, int fromY) {
        if (fromY < 0 || fromY > 511) {
            return 0;
        }
        return 1 + ((((blockX & 1023) << 10 | (blockZ & 1023)) << 9) | fromY);
    }

    private static float findGroundBelow(World world, int blockX, int blockZ, int fromY, int maxDrop) {
        // Réutilisée : ceci tourne pour chaque sommet de chaque lumière posée au sol
        BlockPos.MutableBlockPos pos = GROUND_POS;
        for (int y = fromY; y >= fromY - maxDrop; y--) {
            // Toute la colonne tombe dans un seul chunk, cherché une fois pour elle entière
            IBlockState state = blockAt(world, blockX, y, blockZ);
            if (state == null) {
                break;
            }
            if (state.getMaterial() == Material.AIR) {
                continue;
            }
            pos.setPos(blockX, y, blockZ);
            AxisAlignedBB box = state.getCollisionBoundingBox(world, pos);
            if (box == null) {
                // L'eau et la lave reflètent bien les lumières, mais l'herbe et les fleurs sont ignorées
                if (state.getMaterial().isLiquid()) {
                    return y + 0.9f;
                }
                continue;
            }
            return (float) (y + box.maxY);
        }
        return Float.NaN;
    }

    /**
     * Calcule, une fois par image, jusqu'où chaque détail du renderer est gardé : chacun est écrit pour le réglage
     * de distance livré avec le mod et mis à l'échelle de celui du joueur. Ce que cela coûte reste borné, aucun de
     * ces détails n'étant un budget : l'image ne lance jamais que tant de rayons, n'éclaire que tant de faces et ne
     * lit que tant de colonnes, le tout distribué aux lumières les plus proches.
     */
    private static void computeDetailDistances() {
        float scale = MathHelper.clamp(DynamXConfig.lightFxMaxDistance / REFERENCE_MAX_DISTANCE,
                MIN_DETAIL_SCALE, MAX_DETAIL_SCALE);
        // Gardées en inverses : les deux divisent une distance par elles, demandée pour chaque face
        frameFaceStepFactor = 1 / (FACE_STEP_DISTANCE * scale);
        frameConeSegmentFactor = 1 / (CONE_SEGMENT_DISTANCE * scale);
        // Et ces deux-là en carrés, contre les distances au carré que les lumières portent déjà
        double fine = SPOT_FINE_DISTANCE * 2 * scale;
        frameSpotFineDistanceSq = fine * fine;
        double traced = BEAM_TRACE_DISTANCE * scale;
        frameBeamTraceDistanceSq = traced * traced;
    }

    /* État open gl */

    /**
     * Lit le point de vue de l'image : le centre du rendu, la position de la caméra et ses axes en espace monde.
     * Ces deux derniers sont lus dans la matrice modelview plutôt que dans le render manager, qui ne connaît que
     * l'entité de vue : ils sont ainsi justes quoi qu'ait déplacé la caméra.
     */
    private static void readCamera() {
        RenderManager renderManager = ClientEventHandler.MC.getRenderManager();
        originX = renderManager.viewerPosX;
        originY = renderManager.viewerPosY;
        originZ = renderManager.viewerPosZ;

        MODELVIEW_BUFFER.clear();
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, MODELVIEW_BUFFER);
        // Matrice stockée en colonnes : la première ligne de sa rotation est l'axe droit, la seconde l'axe haut
        float m00 = MODELVIEW_BUFFER.get(0), m10 = MODELVIEW_BUFFER.get(1), m20 = MODELVIEW_BUFFER.get(2);
        float m01 = MODELVIEW_BUFFER.get(4), m11 = MODELVIEW_BUFFER.get(5), m21 = MODELVIEW_BUFFER.get(6);
        float m02 = MODELVIEW_BUFFER.get(8), m12 = MODELVIEW_BUFFER.get(9), m22 = MODELVIEW_BUFFER.get(10);
        float tx = MODELVIEW_BUFFER.get(12), ty = MODELVIEW_BUFFER.get(13), tz = MODELVIEW_BUFFER.get(14);
        rightX = m00;
        rightY = m01;
        rightZ = m02;
        upX = m10;
        upY = m11;
        upZ = m12;
        // La matrice envoie un point p sur r * (p - c), r étant sa rotation et c la caméra : celle-ci est donc à
        // -(r transposée) * (partie translation de la matrice), relativement au centre du rendu
        double offsetX = -(m00 * tx + m10 * ty + m20 * tz);
        double offsetY = -(m01 * tx + m11 * ty + m21 * tz);
        double offsetZ = -(m02 * tx + m12 * ty + m22 * tz);
        if (Double.isNaN(offsetX) || Double.isNaN(offsetY) || Double.isNaN(offsetZ)) {
            // Ne devrait jamais arriver, mais une matrice cassée ne doit pas envoyer les lumières nulle part
            offsetX = offsetY = offsetZ = 0;
        }
        cameraX = originX + offsetX;
        cameraY = originY + offsetY;
        cameraZ = originZ + offsetZ;
        readFrustum();
    }

    /**
     * Lit les six côtés du champ de vision dans les matrices de l'image : leur produit envoie un point du monde
     * dans le cube où tombe tout ce qui est à l'écran, et les lignes de cette matrice sont les plans de ses côtés.
     * Laissés non normalisés exprès : on ne leur demande que de quel côté se trouve quelque chose.
     */
    private static void readFrustum() {
        PROJECTION_BUFFER.clear();
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, PROJECTION_BUFFER);
        // Les deux matrices sont stockées en colonnes et le produit gardé en lignes : les plans sont ses lignes
        for (int row = 0; row < 4; row++) {
            for (int column = 0; column < 4; column++) {
                float sum = 0;
                for (int step = 0; step < 4; step++) {
                    sum += PROJECTION_BUFFER.get(step * 4 + row) * MODELVIEW_BUFFER.get(column * 4 + step);
                }
                VIEW_PROJECTION[row * 4 + column] = sum;
            }
        }
        for (int axis = 0; axis < 3; axis++) {
            // Le côté proche et le côté lointain de chaque axe : la dernière ligne plus et moins celle de l'axe
            for (int at = 0; at < 4; at++) {
                FRUSTUM_PLANES[axis * 8 + at] = VIEW_PROJECTION[12 + at] + VIEW_PROJECTION[axis * 4 + at];
                FRUSTUM_PLANES[axis * 8 + 4 + at] = VIEW_PROJECTION[12 + at] - VIEW_PROJECTION[axis * 4 + at];
            }
        }
    }

    /**
     * Dit si une partie d'une boîte du monde peut être vue par la caméra : elle est testée contre chaque côté du
     * frustum, sur celui de ses coins qui dépasse le plus ce côté.
     */
    private static boolean inFrustum(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        // Les matrices envoient le monde relativement au centre du rendu, où les sommets sont aussi donnés
        minX -= originX;
        minY -= originY;
        minZ -= originZ;
        maxX -= originX;
        maxY -= originY;
        maxZ -= originZ;
        for (int plane = 0; plane < FRUSTUM_PLANES.length; plane += 4) {
            float normalX = FRUSTUM_PLANES[plane], normalY = FRUSTUM_PLANES[plane + 1],
                    normalZ = FRUSTUM_PLANES[plane + 2];
            double corner = normalX * (normalX > 0 ? maxX : minX) + normalY * (normalY > 0 ? maxY : minY)
                    + normalZ * (normalZ > 0 ? maxZ : minZ);
            if (corner + FRUSTUM_PLANES[plane + 3] < 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Dit si quelque chose de ce qu'une lumière dessine peut être vu par la caméra, demandé avant tout calcul la
     * concernant. Ce qu'elle couvre est mesuré sur ce qu'elle dessine vraiment : un projecteur porte le long de son
     * faisceau, soit une boîte de quelques blocs devant le véhicule, là où une boîte autour de la lampe garderait à
     * l'écran l'essentiel des lampes d'une rue.
     */
    private static boolean lightInFrustum(LightFxEntry entry) {
        // Une lampe dessine toujours son propre halo, soit une rangée de billboards sur toute sa longueur
        float around = MAX_FLARE_SIZE * BEACON_FLARE_SIZE * FLARE_BLOOM_SIZE
                + MathHelper.sqrt(entry.glowSpanX * entry.glowSpanX + entry.glowSpanY * entry.glowSpanY
                + entry.glowSpanZ * entry.glowSpanZ);
        double minX = entry.x - around, minY = entry.y - around, minZ = entry.z - around;
        double maxX = entry.x + around, maxY = entry.y + around, maxZ = entry.z + around;
        if (entry.spot) {
            // Le long du faisceau, aussi large que sa lumière, et jusqu'au sol le plus profond atteignable
            float length = Math.min(entry.radius * SPOT_THROW, MAX_BEAM_LENGTH);
            float width = Math.min(entry.groundRadius, MAX_POOL_RADIUS) * SPOT_WIDTH_FAR + 1;
            double endX = entry.emitX + entry.beamX * length, endY = entry.emitY + entry.beamY * length,
                    endZ = entry.emitZ + entry.beamZ * length;
            minX = Math.min(minX, Math.min(entry.emitX, endX) - width);
            minY = Math.min(minY, Math.min(entry.emitY, endY) - width - length - GROUND_EXTRA_DROP);
            minZ = Math.min(minZ, Math.min(entry.emitZ, endZ) - width);
            maxX = Math.max(maxX, Math.max(entry.emitX, endX) + width);
            maxY = Math.max(maxY, Math.max(entry.emitY, endY) + width);
            maxZ = Math.max(maxZ, Math.max(entry.emitZ, endZ) + width);
        } else if (entry.beamCount > 0) {
            // Les faisceaux d'un gyrophare balaient tout autour de lui : cette boîte est bien un cube
            float reach = Math.min(entry.radius * 1.5f, MAX_BEAM_LENGTH) + MIN_PATCH_RADIUS;
            minX = Math.min(minX, entry.emitX - reach);
            minY = Math.min(minY, entry.emitY - reach);
            minZ = Math.min(minZ, entry.emitZ - reach);
            maxX = Math.max(maxX, entry.emitX + reach);
            maxY = Math.max(maxY, entry.emitY + reach);
            maxZ = Math.max(maxZ, entry.emitZ + reach);
        }
        return inFrustum(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /**
     * Prépare l'état open gl pour dessiner les effets. Seul l'état laissé par le rendu du monde est touché, et
     * {@link DynamXLightFx#restoreRender} le rend entièrement : rien ici ne doit fuir dans le reste de l'image.
     */
    private static void setupRender() {
        // Lit tout ce qui va être changé, pour que restoreRender le remette exactement tel quel
        snapshotGlState(GL_BEFORE);
        blendSrcBefore = GL11.glGetInteger(GL11.GL_BLEND_SRC);
        blendDstBefore = GL11.glGetInteger(GL11.GL_BLEND_DST);
        shadeModelBefore = GL11.glGetInteger(GL11.GL_SHADE_MODEL);

        GlStateManager.disableLighting();
        ClientEventHandler.MC.entityRenderer.disableLightmap();
        // Le brouillard est laissé tel quel : GlStateManager met son drapeau en cache, et un cache en désaccord
        // avec le driver laisse tout le rendu du monde sans brouillard ensuite
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        // Un seul mode de blending, qui ne fait qu'ajouter de la lumière sans changer les couleurs derrière
        GlStateManager.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        GlStateManager.disableAlpha();
        GlStateManager.enableDepth();
        // Test de profondeur gardé pour être masqué par les murs, sans écrire le depth buffer
        GlStateManager.depthMask(false);
        GlStateManager.disableCull();
        GlStateManager.shadeModel(GL11.GL_SMOOTH);
        GlStateManager.color(1, 1, 1, 1);
    }

    /**
     * Remet l'état open gl exactement tel que {@link DynamXLightFx#setupRender} l'a trouvé, et non à ce que le rendu
     * du monde est supposé laisser : tout ce qui est laissé de travers ici fuit dans le reste de l'image.
     */
    private static void restoreRender() {
        GlStateManager.shadeModel(shadeModelBefore);
        GlStateManager.blendFunc(blendSrcBefore, blendDstBefore);
        GlStateManager.depthMask(GL_BEFORE[FLAG_DEPTH_WRITE]);
        GlStateManager.color(1, 1, 1, 1);

        if (GL_BEFORE[FLAG_CULL]) {
            GlStateManager.enableCull();
        } else {
            GlStateManager.disableCull();
        }
        if (GL_BEFORE[FLAG_ALPHA]) {
            GlStateManager.enableAlpha();
        } else {
            GlStateManager.disableAlpha();
        }
        if (GL_BEFORE[FLAG_BLEND]) {
            GlStateManager.enableBlend();
        } else {
            GlStateManager.disableBlend();
        }
        if (GL_BEFORE[FLAG_DEPTH]) {
            GlStateManager.enableDepth();
        } else {
            GlStateManager.disableDepth();
        }
        if (GL_BEFORE[FLAG_LIGHTING]) {
            GlStateManager.enableLighting();
        } else {
            GlStateManager.disableLighting();
        }
        if (GL_BEFORE[FLAG_FOG]) {
            GlStateManager.enableFog();
        } else {
            GlStateManager.disableFog();
        }
        // Le lightmap possède son texture unit, et ces deux-là laissent le défaut actif ensuite
        if (GL_BEFORE[FLAG_LIGHTMAP]) {
            ClientEventHandler.MC.entityRenderer.enableLightmap();
        } else {
            ClientEventHandler.MC.entityRenderer.disableLightmap();
        }
        // En dernier, car cela s'applique au texture unit actif
        if (GL_BEFORE[FLAG_TEXTURE]) {
            GlStateManager.enableTexture2D();
        } else {
            GlStateManager.disableTexture2D();
        }
    }
}
