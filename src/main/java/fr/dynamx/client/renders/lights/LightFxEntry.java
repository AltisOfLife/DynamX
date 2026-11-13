package fr.dynamx.client.renders.lights;

import net.minecraft.util.EnumFacing;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Une lumière allumée, soumise par le scene graph pendant le rendu des entités et des blocs, puis consommée par
 * {@link DynamXLightFx} à la fin de l'image. Les instances sont recyclées d'une image à l'autre.
 */
@SideOnly(Side.CLIENT)
class LightFxEntry {
    static final int MAX_BEAMS = 8;
    /** Occulteurs max par lumière : au-delà, seuls les plus proches sont gardés */
    static final int MAX_OCCLUDERS = 8;

    /** Position de la lampe dans le monde, où son halo est dessiné */
    double x, y, z;
    /** Point d'émission : base de la lampe, pour que le faisceau en sorte et non en l'air devant elle */
    double emitX, emitY, emitZ;
    /**
     * Demi-longueur de la lampe, en vecteur monde (zéro pour une lampe ponctuelle). Le halo est étalé le long de ce
     * vecteur pour qu'une rampe lumineuse brille sur toute sa longueur.
     */
    float glowSpanX, glowSpanY, glowSpanZ;
    /**
     * Couleur de chaque tronçon de la lampe, de l'extrémité opposée au glowSpan vers l'autre, ou null pour une
     * lampe d'une seule couleur : une rampe bicolore donne alors un halo par tronçon.
     *
     * @see fr.dynamx.common.contentpack.parts.LightFxLamp#getUvStretches()
     */
    int[] glowColors;
    /**
     * Entité porteuse, ou -1 pour un bloc. Un véhicule ne bloque jamais sa propre lumière, la lampe étant dans sa
     * bounding box.
     */
    int ownerEntityId = -1;
    float red, green, blue;
    /** Portée, en blocs */
    float radius;
    /** Rayon de la tache au sol d'un projecteur, 0 s'il n'en projette pas */
    float groundRadius;
    /** Multiplicateur de luminosité, avant la lumière ambiante et la distance */
    float intensity;
    int beamCount;
    boolean beacon;
    /**
     * Vrai si ce gyrophare tourne sur lui-même : ses faisceaux balaient et la lampe flashe au passage devant la
     * caméra. Un gyrophare qui ne fait que clignoter éclaire uniformément autour de lui.
     */
    boolean sweeping;
    /** Vrai si projecteur (phare) : éclaire le sol et les surfaces devant lui, sans faisceau visible */
    boolean spot;
    /**
     * Direction normalisée du premier faisceau : les autres sont répartis autour de l'axe vertical pour un
     * gyrophare, et pour un projecteur c'est la direction de visée.
     */
    float beamX, beamY, beamZ;
    /** Demi-largeur du verre, qui donne la taille du halo et la largeur de départ d'un projecteur */
    float lampHalfWidth;
    double cameraDistanceSq;
    /**
     * Identifie cette lampe précise et ne change pas tant que le véhicule est visible : deux lampes à égale
     * distance de la caméra sont départagées par cette clé, et les budgets vont ainsi toujours aux mêmes lampes.
     */
    int stableKey;
    /** Luminosité finale, calculée une fois par image */
    float brightness;
    /** Rempli uniquement quand la capture de débogage de {@link DynamXLightFx} est active */
    String debugName;

    /** Faisceaux réellement résolus cette image, parfois moins que {@link LightFxEntry#beamCount} */
    int resolvedBeams;
    final float[] beamDirX = new float[MAX_BEAMS], beamDirY = new float[MAX_BEAMS], beamDirZ = new float[MAX_BEAMS];
    /** Longueur de chaque faisceau, raccourcie au premier obstacle */
    final float[] beamLength = new float[MAX_BEAMS];
    /** Point d'impact, valable seulement si la face correspondante n'est pas nulle */
    final double[] beamHitX = new double[MAX_BEAMS], beamHitY = new double[MAX_BEAMS], beamHitZ = new double[MAX_BEAMS];
    /**
     * Face touchée, null si le faisceau ne touche rien, et null aussi s'il a été arrêté par un véhicule ou un
     * joueur : ceux-là le coupent sans jamais être éclairés
     */
    final EnumFacing[] beamHitFace = new EnumFacing[MAX_BEAMS];
    final int[] beamHitBlockX = new int[MAX_BEAMS], beamHitBlockY = new int[MAX_BEAMS],
            beamHitBlockZ = new int[MAX_BEAMS];

    /* Entités dans la lumière (véhicules, joueurs, mobs) : elles coupent les faisceaux et ombrent ce qui est
       derrière, sans jamais recevoir de lumière. Seules les occluderCount premières boîtes sont valables, chacune
       en min x, y, z puis max x, y, z. */

    final double[] occluders = new double[MAX_OCCLUDERS * 6];
    /** Distance au carré de chaque occulteur à la lampe, ordre dans lequel ils sont rangés */
    final double[] occluderDistanceSq = new double[MAX_OCCLUDERS];
    int occluderCount;

    /* Empreinte au sol de la lumière d'un projecteur, calculée une fois par image. Sans objet si projectionLength
       vaut 0. */

    /** Portée complète du projecteur : ce qui en masque une partie est traité bloc par bloc à la pose */
    float projectionLength;
    /** Demi-largeur de la projection à la lampe, puis à son extrémité */
    float projectionWidthNear, projectionWidthFar;
    /**
     * Part de lumière réellement posée, entre 0 et 1 : les derniers projecteurs du budget sont fondus plutôt que
     * coupés net, voir {@link DynamXLightFx#PROJECTION_FADE_FROM}
     */
    float projectionFade = 1;
    /**
     * Distance, le long du faisceau, de la première surface trouvée dressée dedans, ou {@link Float#MAX_VALUE} si
     * aucune : au-delà, le sol doit être tracé point par point, un obstacle dans le faisceau étant la seule chose
     * qui puisse ombrer un sol parfaitement plat ; en deçà, rien ne bloque et aucun rayon n'est nécessaire. Les
     * véhicules et les joueurs ne comptent pas ici, ils sont testés sur leurs propres boîtes.
     */
    float projectionBlockedFrom = Float.MAX_VALUE;

    void set(double x, double y, double z, int color, float radius, float groundRadius, float intensity,
             int beamCount, boolean beacon, boolean sweeping, boolean spot, float beamX, float beamY, float beamZ) {
        this.x = x;
        this.y = y;
        this.z = z;
        // Écrasés juste après par l'appelant, qui connaît la lampe
        this.emitX = x;
        this.emitY = y;
        this.emitZ = z;
        this.glowSpanX = 0;
        this.glowSpanY = 0;
        this.glowSpanZ = 0;
        this.glowColors = null;
        this.ownerEntityId = -1;
        this.stableKey = 0;
        this.occluderCount = 0;
        this.red = ((color >> 16) & 0xFF) / 255f;
        this.green = ((color >> 8) & 0xFF) / 255f;
        this.blue = (color & 0xFF) / 255f;
        this.radius = radius;
        this.groundRadius = groundRadius;
        this.intensity = intensity;
        this.beamCount = Math.min(beamCount, MAX_BEAMS);
        this.beacon = beacon;
        this.sweeping = sweeping;
        this.spot = spot;
        this.beamX = beamX;
        this.beamY = beamY;
        this.beamZ = beamZ;
        this.resolvedBeams = 0;
        this.projectionLength = 0;
        this.projectionWidthNear = 0;
        this.projectionWidthFar = 0;
        this.projectionFade = 1;
        this.projectionBlockedFrom = Float.MAX_VALUE;
    }
}
