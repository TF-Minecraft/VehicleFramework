package net.tfminecraft.vehicleframework.util;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import net.tfminecraft.vehicleframework.loaders.VehicleLoader;
import net.tfminecraft.vehicleframework.permissions.Permissions;
import net.tfminecraft.vehicleframework.vehicles.Vehicle;

public class TabCompletion implements TabCompleter {
    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) return List.of();
        List<String> options = candidates(sender, cmd, args);
        if (options == null) return null;
        String prefix = args[args.length - 1];
        return options.stream().filter(option -> option.regionMatches(true, 0, prefix, 0, prefix.length())).toList();
    }

    private List<String> candidates(CommandSender sender, Command cmd, String[] args) {
        if (!(sender instanceof Player)) {
            if (!cmd.getName().equalsIgnoreCase("vf") || !Permissions.isAdmin(sender)) return List.of();
            if (args.length == 1) return List.of("reload", "admin", "track");
            if (args.length == 2 && args[0].equalsIgnoreCase("admin")) return List.of("quarantine");
            if (args.length == 2 && args[0].equalsIgnoreCase("track")) return List.of("resync");
            return List.of();
        }
        List<String> completions = new ArrayList<>();

        if (cmd.getName().equalsIgnoreCase("vf")) {
            if (args.length == 1) {
                completions.add("findvehicles");
                completions.add("keybinds");
                if(Permissions.canSpawn(sender)) {
                    completions.add("spawn");
                    completions.add("kill");
                    completions.add("ammo");
                }
				if (Permissions.isAdmin(sender)) {
					completions.add("takeover");
                    completions.add("reload");
                    completions.add("track");
                    completions.add("admin");
                }
                return completions;
            }

            if (args[0].equalsIgnoreCase("admin") && args.length == 2 && Permissions.isAdmin(sender)) {
                completions.add("quarantine");
                return completions;
            }

            if (args[0].equalsIgnoreCase("takeover") && args.length >= 2 && Permissions.isAdmin(sender)) {
                return net.tfminecraft.vehicleframework.managers.OwnershipManager.takeoverSuggestions(
                        java.util.Arrays.copyOfRange(args, 1, args.length));
            }

            if (args[0].equalsIgnoreCase("spawn") && args.length == 2) {
                if (!Permissions.canSpawn(sender)) return completions;
                for (Vehicle v : VehicleLoader.get().values()) {
                    completions.add(v.getId());
                }
                return completions;
            }

            if (args[0].equalsIgnoreCase("track") && Permissions.isAdmin(sender)) {
                if (args.length == 2) {
                    completions.add("start");
                    completions.add("end");
                    completions.add("list");
                    completions.add("info");
                    completions.add("particles");
                    completions.add("clearance");
                    completions.add("dump");
                    completions.add("delete");
                    completions.add("bind");
                    completions.add("unbind");
                    completions.add("resync");
                    return completions;
                }
                if (args.length == 3 && sender instanceof Player player
                        && List.of("delete", "clearance", "info", "particles", "bind")
                                .contains(args[1].toLowerCase(java.util.Locale.ROOT))) {
                    var registry = net.tfminecraft.vehicleframework.VehicleFramework.getTrackRegistry();
                    if (registry != null) {
                        for (net.tfminecraft.vehicleframework.tracks.TrackSpline spline
                                : registry.inWorld(player.getWorld().getName())) {
                            completions.add(spline.getId().toString());
                        }
                    }
                    return completions;
                }
            }

            if (args[0].equalsIgnoreCase("kill") && args.length == 2) {
                completions.add("10");  // Suggest a default radius
                completions.add("20");
                completions.add("50");
                return completions;
            }
        }

        return null;
    }
}
