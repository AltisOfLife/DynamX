package fr.dynamx.utils;

import javax.annotation.Nullable;

/**
 * Réglages prédéfinis des effets de lumière, partagés par le menu graphique et la commande pour qu'ils donnent
 * exactement le même résultat.
 *
 * @see fr.dynamx.client.renders.lights.DynamXLightFx
 */
public enum LightFxPreset {
    ULTRA("ultra", "Ultra", 96, 96, false, false),
    HIGH("high", "Élevé", 64, 64, false, false),
    MEDIUM("medium", "Moyen", 48, 32, false, false),
    LOW("low", "Bas", 32, 16, false, false),
    /**
     * Les gyrophares uniquement : le préréglage le moins coûteux
     */
    MINIMAL("minimal", "Minimal", 32, 8, true, false);

    /**
     * Nom tapé dans la commande, indépendant de la langue du jeu
     */
    private final String name;
    /**
     * Nom affiché dans le menu graphique
     */
    private final String displayName;
    private final int maxDistance, maxLights;
    private final boolean beaconsOnly, ownVehicleOnly;

    LightFxPreset(String name, String displayName, int maxDistance, int maxLights, boolean beaconsOnly,
                  boolean ownVehicleOnly) {
        this.name = name;
        this.displayName = displayName;
        this.maxDistance = maxDistance;
        this.maxLights = maxLights;
        this.beaconsOnly = beaconsOnly;
        this.ownVehicleOnly = ownVehicleOnly;
    }

    public String getName() {
        return name;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * Active les effets et applique tous les réglages du préréglage, sauvegardés en une seule fois
     */
    public void apply() {
        DynamXConfig.setLightFxSettings(true, maxDistance, maxLights, beaconsOnly, ownVehicleOnly);
    }

    /**
     * @return Vrai si les réglages actuels sont exactement ceux de ce préréglage
     */
    public boolean isApplied() {
        return DynamXConfig.enableLightFx && DynamXConfig.lightFxMaxDistance == maxDistance
                && DynamXConfig.lightFxMaxLights == maxLights && DynamXConfig.lightFxBeaconsOnly == beaconsOnly
                && DynamXConfig.lightFxOwnVehicleOnly == ownVehicleOnly;
    }

    /**
     * @return Le préréglage portant ce nom (insensible à la casse), ou null
     */
    @Nullable
    public static LightFxPreset byName(String name) {
        for (LightFxPreset preset : values()) {
            if (preset.name.equalsIgnoreCase(name)) {
                return preset;
            }
        }
        return null;
    }

    /**
     * @return Le nom de tous les préréglages, pour la complétion de la commande
     */
    public static String[] names() {
        String[] names = new String[values().length];
        for (int i = 0; i < names.length; i++) {
            names[i] = values()[i].name;
        }
        return names;
    }
}
