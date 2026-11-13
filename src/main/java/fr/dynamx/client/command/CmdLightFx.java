package fr.dynamx.client.command;

import fr.dynamx.client.renders.lights.DynamXLightFx;
import fr.dynamx.common.command.ISubCommand;
import fr.dynamx.utils.DynamXConfig;
import fr.dynamx.utils.LightFxPreset;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Active, désactive et règle les effets de lumière des lumières allumées. Côté client uniquement : les réglages sont
 * sauvegardés dans la config DynamX du joueur.
 *
 * @see DynamXLightFx
 */
public class CmdLightFx implements ISubCommand {
    @Override
    public String getName() {
        return "lightfx";
    }

    @Override
    public String getUsage() {
        return "/client_dynamx lightfx [on|off|preset <ultra|high|medium|low|minimal>|intensity <0-3>"
                + "|beacons <on|off>|myvehicle <on|off>|sweep <on|off>|distance <8-256>|maxlights <1-256>|debug]"
                + " - active, désactive et règle les effets de lumière des lumières allumées";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 2) {
            DynamXConfig.setEnableLightFx(!DynamXConfig.enableLightFx);
            sendState(sender);
            return;
        }
        switch (args[1].toLowerCase()) {
            case "on":
                DynamXConfig.setEnableLightFx(true);
                break;
            case "off":
                DynamXConfig.setEnableLightFx(false);
                DynamXLightFx.clear();
                break;
            case "toggle":
                DynamXConfig.setEnableLightFx(!DynamXConfig.enableLightFx);
                break;
            case "intensity":
                DynamXConfig.setLightFxIntensity((float) CommandBase.parseDouble(getArgument(args), 0, 3));
                break;
            // Pour les trois réglages suivants, "on" restreint les effets (gyrophares seuls, véhicule du joueur
            // seul) ou les anime, "off" fait l'inverse
            case "beacons":
                DynamXConfig.setLightFxBeaconsOnly(!"off".equalsIgnoreCase(getArgument(args)));
                break;
            case "myvehicle":
                DynamXConfig.setLightFxOwnVehicleOnly(!"off".equalsIgnoreCase(getArgument(args)));
                break;
            case "sweep":
                DynamXConfig.setLightFxRotatingBeaconSweep(!"off".equalsIgnoreCase(getArgument(args)));
                break;
            case "preset":
                applyPreset(sender, getArgument(args));
                return;
            case "distance":
                DynamXConfig.setLightFxMaxDistance(CommandBase.parseInt(getArgument(args), 8, 256));
                break;
            case "maxlights":
                DynamXConfig.setLightFxMaxLights(CommandBase.parseInt(getArgument(args), 1, 256));
                break;
            case "debug":
                if (args.length >= 3 && "off".equalsIgnoreCase(args[2])) {
                    DynamXLightFx.setDebugCapture(false);
                    sender.sendMessage(new TextComponentString("Débogage des effets de lumière : "
                            + TextFormatting.RED + "désactivé"));
                } else if (DynamXLightFx.isDebugCapture()) {
                    // Capture déjà active : on rapporte le contenu de la dernière image
                    sendLastFrame(sender);
                } else {
                    DynamXLightFx.setDebugCapture(true);
                    sender.sendMessage(new TextComponentString("Débogage des effets de lumière : "
                            + TextFormatting.GREEN + "activé" + TextFormatting.RESET + ", regardez une lumière"
                            + " allumée et relancez la commande pour voir comment elle a été interprétée"
                            + " ('debug off' pour arrêter)"));
                }
                return;
            default:
                throw new WrongUsageException(getUsage());
        }
        sendState(sender);
    }

    /**
     * Applique tous les réglages d'un coup
     *
     * @param name Le nom du préréglage, tel qu'il est tapé dans la commande
     */
    private void applyPreset(ICommandSender sender, String name) throws CommandException {
        LightFxPreset preset = LightFxPreset.byName(name);
        if (preset == null) {
            throw new WrongUsageException(getUsage());
        }
        preset.apply();
        sender.sendMessage(new TextComponentString("Préréglage des effets de lumière DynamX : "
                + TextFormatting.GREEN + preset.getDisplayName()));
        sendState(sender);
    }

    /**
     * Envoie ce que le renderer a fait des lumières de la dernière image, pour qu'un packmaker voie comment les
     * siennes ont été interprétées (type, position de la lampe, luminosité...)
     */
    private void sendLastFrame(ICommandSender sender) {
        List<String> report = DynamXLightFx.getLastFrameReport();
        if (report.isEmpty()) {
            sender.sendMessage(new TextComponentString(TextFormatting.RED
                    + "Aucune lumière n'a été collectée à la dernière image : aucune lumière allumée n'est visible,"
                    + " ou les effets sont désactivés"));
            return;
        }
        report.forEach(line -> sender.sendMessage(new TextComponentString(TextFormatting.GRAY + line)));
    }

    /**
     * @return La valeur donnée à la sous-commande courante
     */
    private String getArgument(String[] args) throws CommandException {
        if (args.length < 3) {
            throw new WrongUsageException(getUsage());
        }
        return args[2];
    }

    private void sendState(ICommandSender sender) {
        sender.sendMessage(new TextComponentString("Effets de lumière DynamX : "
                + (DynamXConfig.enableLightFx ? TextFormatting.GREEN + "activés" : TextFormatting.RED + "désactivés")
                + TextFormatting.RESET
                + " (intensité : " + DynamXConfig.lightFxIntensity
                + ", distance : " + DynamXConfig.lightFxMaxDistance
                + ", lumières max : " + DynamXConfig.lightFxMaxLights
                + (DynamXConfig.lightFxBeaconsOnly ? ", gyrophares uniquement" : "")
                + (DynamXConfig.lightFxRotatingBeaconSweep ? "" : ", sans animation des gyrophares rotatifs")
                + (DynamXConfig.lightFxOwnVehicleOnly ? ", mon véhicule uniquement" : "") + ")"));
    }

    @Override
    public void getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, @Nullable BlockPos targetPos, List<String> r) {
        if (args.length == 2) {
            r.addAll(CommandBase.getListOfStringsMatchingLastWord(args, "on", "off", "toggle", "preset", "intensity",
                    "beacons", "myvehicle", "sweep", "distance", "maxlights", "debug"));
        } else if (args.length == 3 && "debug".equalsIgnoreCase(args[1])) {
            r.addAll(CommandBase.getListOfStringsMatchingLastWord(args, "off"));
        } else if (args.length == 3 && "preset".equalsIgnoreCase(args[1])) {
            r.addAll(CommandBase.getListOfStringsMatchingLastWord(args, LightFxPreset.names()));
        } else if (args.length == 3 && ("beacons".equalsIgnoreCase(args[1])
                || "myvehicle".equalsIgnoreCase(args[1]) || "sweep".equalsIgnoreCase(args[1]))) {
            r.addAll(CommandBase.getListOfStringsMatchingLastWord(args, "on", "off"));
        }
    }
}
