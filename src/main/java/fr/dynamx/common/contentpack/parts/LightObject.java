package fr.dynamx.common.contentpack.parts;

import com.jme3.math.Vector3f;
import fr.dynamx.api.contentpack.object.subinfo.ISubInfoType;
import fr.dynamx.api.contentpack.object.subinfo.ISubInfoTypeOwner;
import fr.dynamx.api.contentpack.registry.DefinitionType;
import fr.dynamx.api.contentpack.registry.PackFileProperty;
import fr.dynamx.api.contentpack.registry.RegisteredSubInfoType;
import fr.dynamx.api.contentpack.registry.SubInfoTypeRegistries;
import fr.dynamx.client.renders.model.texture.TextureVariantData;
import lombok.Getter;
import lombok.Setter;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * A {@link LightObject} is the link between an obj part and a light (designed by its id) <br>
 * One part can have multiple {@link LightObject}
 *
 * @see PartLightSource
 */
@Setter
public class LightObject {
    @PackFileProperty(configNames = "LightId")
    protected String lightId = "";
    @Getter
    @PackFileProperty(configNames = "Textures", defaultValue = "Textures: Light_On")
    protected String[] textures;
    @Getter
    @PackFileProperty(configNames = "BlinkSequenceTicks", required = false, defaultValue = "none")
    protected int[] blinkSequence;
    @Getter
    @PackFileProperty(configNames = "RotateDuration", required = false, defaultValue = "0")
    protected int rotateDuration;

    /* Effets de lumière : halo, lumière projetée au sol et faisceaux dessinés autour d'une lumière allumée.
       Purement visuels et côté client uniquement, voir fr.dynamx.client.renders.lights.DynamXLightFx */

    /**
     * Couleur des effets, en "rouge vert bleu" (0-255 chacun) <br>
     * Devinée depuis les pixels les plus lumineux de la texture allumée si elle n'est pas définie
     */
    @Getter
    @PackFileProperty(configNames = "LightColor", type = DefinitionType.DynamXDefinitionTypes.INT_ARRAY, required = false, defaultValue = "Guessed from the light texture")
    protected int[] lightColor;
    /**
     * Portée des effets, en blocs
     */
    @Getter
    @PackFileProperty(configNames = "GlowRadius", required = false, defaultValue = "10")
    protected float glowRadius = 10;
    /**
     * Multiplicateur de luminosité des effets. 0 les désactive pour cette lumière.
     */
    @Getter
    @PackFileProperty(configNames = "GlowIntensity", required = false, defaultValue = "1")
    protected float glowIntensity = 1;
    /**
     * Type d'effets : <code>Auto</code> (deviné, voir {@link EnumGlowType#AUTO}), <code>None</code> (aucun),
     * <code>Glow</code> (le halo de la lampe seul), <code>Spot</code> (un projecteur, qui éclaire aussi le sol
     * et les surfaces devant lui) ou <code>Beacon</code> (un gyrophare, qui émet des faisceaux balayants)
     */
    @PackFileProperty(configNames = "GlowType", type = DefinitionType.DynamXDefinitionTypes.STRING, required = false, defaultValue = "Auto")
    protected String glowType;
    /**
     * Direction visée par un <code>Spot</code>, dans l'espace du modèle 3D, en "x y z" <br>
     * Par défaut vers l'avant du modèle (ou vers l'arrière si la lampe est derrière son centre), légèrement
     * inclinée vers le bas
     */
    @Getter
    @PackFileProperty(configNames = "GlowDirection", type = DefinitionType.DynamXDefinitionTypes.VECTOR3F, required = false, defaultValue = "Towards the front of the model")
    protected Vector3f glowDirection;
    /**
     * Nombre de faisceaux balayants d'un <code>Beacon</code>, répartis autour de son axe vertical. Négatif pour
     * laisser DynamX décider (deux faisceaux).
     */
    @PackFileProperty(configNames = "GlowBeams", required = false, defaultValue = "2")
    protected int glowBeams = -1;
    /**
     * Rayon, en blocs, de la lumière qu'un <code>Spot</code> projette au sol devant lui, 0 pour aucune. Négatif
     * pour laisser DynamX décider : seul un <code>Spot</code> éclaire le sol, les autres ne font que briller et
     * balayer leurs faisceaux.
     */
    @PackFileProperty(configNames = "GroundLightRadius", required = false, defaultValue = "GlowRadius * 0.6 for a spot, 0 otherwise")
    protected float groundLightRadius = -1;

    protected int lightIdHashed;

    @Getter
    private final List<TextureVariantData> blinkTextures = new ArrayList<>();

    protected void hashLightId() {
        try {
            lightIdHashed = Integer.parseInt(lightId);
        } catch (NumberFormatException e) {
            lightIdHashed = lightId.hashCode();
        }
    }

    /**
     * @return The hashed light id (the light id if it's an int, or the hash if it's a string) <br>
     * In {@link fr.dynamx.common.entities.modules.AbstractLightsModule}, it can be used to get the light (or you can use the string version of the id)
     */
    public int getLightId() {
        return lightIdHashed;
    }

    /**
     * @return The light id as a string
     */
    public String getLightIdString() {
        return lightId;
    }

    /**
     * Le type d'effets projetés par une lumière
     */
    public enum EnumGlowType {
        /**
         * Deviné : {@link EnumGlowType#BEACON} si la lumière clignote au-dessus du milieu du modèle,
         * {@link EnumGlowType#SPOT} si c'est une lampe fixe placée devant son centre (un phare), et
         * {@link EnumGlowType#NONE} pour toutes les autres (feu arrière, stop, clignotant), que la texture
         * allumée montre déjà seule. Mettre {@link EnumGlowType#GLOW} pour qu'une de celles-là brille quand même.
         */
        AUTO,
        /**
         * Aucun effet
         */
        NONE,
        /**
         * Le halo de la lampe seul
         */
        GLOW,
        /**
         * Un projecteur : le halo de la lampe, et la lumière projetée au sol et sur les surfaces devant lui
         */
        SPOT,
        /**
         * Un gyrophare : le halo de la lampe, et les faisceaux qui balaient autour d'elle
         */
        BEACON;

        /**
         * @return Le type portant ce nom, ou {@link EnumGlowType#AUTO} s'il n'en existe aucun
         */
        public static EnumGlowType fromString(@Nullable String name) {
            if (name != null) {
                for (EnumGlowType type : values()) {
                    if (type.name().equalsIgnoreCase(name)) {
                        return type;
                    }
                }
            }
            return AUTO;
        }
    }

    /**
     * @return Le type d'effets configuré, {@link EnumGlowType#AUTO} s'il ne l'a pas été
     */
    public EnumGlowType getGlowType() {
        return EnumGlowType.fromString(glowType);
    }

    /**
     * @return Le nombre de faisceaux balayants émis quand cette lumière est un gyrophare
     */
    public int getGlowBeamCount() {
        return glowBeams >= 0 ? glowBeams : 2;
    }

    /**
     * @param type Le type d'effets résolu, qui ne peut pas être {@link EnumGlowType#AUTO}
     * @return Le rayon, en blocs, de la tache de lumière posée au sol, 0 si aucune. Seul un projecteur éclaire le
     * sol par défaut : un gyrophare est fait pour être vu, et un disque de lumière autour du véhicule ressemble à
     * une flaque lumineuse plutôt qu'à une lampe qui clignote.
     */
    public float getGroundLightRadius(EnumGlowType type) {
        if (groundLightRadius >= 0) {
            return groundLightRadius;
        }
        return type == EnumGlowType.SPOT ? glowRadius * 0.6f : 0;
    }

    /**
     * @return Vrai si cette lumière a une séquence de clignotement, quoi que fasse cette séquence
     */
    public boolean isBlinking() {
        return blinkSequence != null && blinkSequence.length > 0;
    }

    /**
     * Si cette lumière clignote réellement, calculé depuis sa séquence au premier appel : les effets le demandent
     * pour chaque lumière allumée de chaque image, alors que la séquence ne change plus une fois le pack chargé
     */
    private Boolean flashing;

    /**
     * @return Vrai si la séquence éteint réellement la lumière à un moment de son cycle. <br>
     * Les packs écrivent couramment une séquence qui laisse la lampe allumée tout son cycle, comme
     * <code>BlinkSequenceTicks: -1 20</code>, pour déclarer une lampe simplement allumée : avoir une séquence
     * n'indique donc rien, seul son déroulement le dit.
     */
    public boolean isFlashing() {
        if (flashing == null) {
            flashing = runBlinkSequence();
        }
        return flashing;
    }

    /**
     * Déroule tout le cycle de la séquence à la recherche d'un tick où la lumière est éteinte
     *
     * @return Vrai si la lumière est éteinte à un moment de son cycle
     */
    private boolean runBlinkSequence() {
        if (!isBlinking()) {
            return false;
        }
        int period = blinkSequence[blinkSequence.length - 1];
        if (period <= 0) {
            return false;
        }
        for (int tick = 0; tick < period; tick++) {
            if (!isOnAt(tick)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Déroule la séquence de clignotement, comme le fait le renderer
     *
     * @param tick Un tick du cycle de la séquence
     * @return Vrai si la lumière est allumée à ce tick
     */
    protected boolean isOnAt(int tick) {
        int mod = tick % blinkSequence[blinkSequence.length - 1];
        for (int i = blinkSequence.length - 1; i >= 0; i--) {
            if (mod > blinkSequence[i]) {
                return i % 2 == 0;
            }
        }
        return false;
    }

    /**
     * @return Vrai si cette lumière tourne sur elle-même (un gyrophare)
     */
    public boolean isRotating() {
        return rotateDuration > 0;
    }

    @RegisteredSubInfoType(name = "LightObject", registries = {SubInfoTypeRegistries.WHEELED_VEHICLES, SubInfoTypeRegistries.HELICOPTER, SubInfoTypeRegistries.BLOCKS, SubInfoTypeRegistries.PROPS}, strictName = false)
    public static class SubLightObject extends LightObject implements ISubInfoType<PartLightSource> {
        protected final PartLightSource owner;

        public SubLightObject(ISubInfoTypeOwner<PartLightSource> owner) {
            this.owner = (PartLightSource) owner;
        }

        @Override
        public void appendTo(PartLightSource owner) {
            hashLightId();
            owner.addLightSource(this);
        }

        @Override
        public String getName() {
            return "LightObject in " + owner.getPartName();
        }

        @Override
        public String getPackName() {
            return owner.getPackName();
        }

        @Nullable
        @Override
        public PartLightSource getOwner() {
            return owner;
        }
    }
}
