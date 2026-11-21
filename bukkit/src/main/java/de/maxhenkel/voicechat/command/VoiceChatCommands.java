package de.maxhenkel.voicechat.command;

import de.maxhenkel.voicechat.BuildConstants;
import de.maxhenkel.voicechat.Voicechat;
import de.maxhenkel.voicechat.net.NetManager;
import de.maxhenkel.voicechat.permission.PermissionManager;
import de.maxhenkel.voicechat.voice.common.PlayerState;
import de.maxhenkel.voicechat.voice.server.ClientConnection;
import de.maxhenkel.voicechat.voice.server.Group;
import de.maxhenkel.voicechat.voice.server.PingManager;
import de.maxhenkel.voicechat.voice.server.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class VoiceChatCommands implements CommandExecutor, TabCompleter {

    public static final String VOICECHAT_COMMAND = "voicechat";

    @Override
    public boolean onCommand(CommandSender commandSender, Command command, String label, String[] args) {
        if (args.length >= 1 && (args[0].equalsIgnoreCase("status") || args[0].equalsIgnoreCase("mute") || args[0].equalsIgnoreCase("unmute"))) {
            return protectionCommand(commandSender, args);
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("report")) return reportCommand(commandSender, args);
        if (checkNoVoicechat(commandSender)) {
            return true;
        }
        if (!(commandSender instanceof Player)) {
            return true;
        }
        Player sender = (Player) commandSender;

        if (args.length >= 1) {
            if (args[0].equalsIgnoreCase("help")) {
                return helpCommand(sender, command, label, args);
            } else if (args[0].equalsIgnoreCase("test")) {
                if (commandSender.hasPermission(PermissionManager.ADMIN_PERMISSION)) {
                    return testCommand(sender, command, label, args);
                } else {
                    return true;
                }
            } else if (args[0].equalsIgnoreCase("invite")) {
                return inviteCommand(sender, command, label, args);
            } else if (args[0].equalsIgnoreCase("join")) {
                return joinCommand(sender, command, label, args);
            } else if (args[0].equalsIgnoreCase("leave")) {
                return leaveCommand(sender, command, label, args);
            }
        }
        return helpCommand(sender, command, label, args);
    }

    @Nullable
    @Override
    public List<String> onTabComplete(CommandSender commandSender, Command command, String label, String[] args) {
        if (args.length == 1) {
            return tabCompleteList(args[0], Arrays.asList("help", "test", "invite", "join", "leave", "status", "mute", "unmute", "report"));
        }

        if (args.length == 2) {
            String arg = args[0];
            if (arg.equals("test") || arg.equals("invite")) {
                return null;
            } else if (arg.startsWith("join")) {
                return Collections.emptyList();
            }
        }

        if (args.length == 3) {
            String arg = args[0];
            if (arg.startsWith("join")) {
                return Collections.emptyList();
            }
        }

        return Collections.emptyList();
    }

    private boolean protectionCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission(PermissionManager.ADMIN_PERMISSION)) { sender.sendMessage("You do not have permission to manage voice chat."); return true; }
        Server server = Voicechat.SERVER.getServer();
        if (server == null) { sender.sendMessage("Voice chat is unavailable."); return true; }
        if (args[0].equalsIgnoreCase("status")) {
            var status = server.getProtectionManager().snapshot();
            sender.sendMessage(String.format(java.util.Locale.ROOT, "Voice bandwidth: in=%.2f Mbps, out=%.2f Mbps, server=%s, admission=%s",
                    status.inboundBytesPerSecond() * 8D / 1_000_000D, status.outboundBytesPerSecond() * 8D / 1_000_000D,
                    status.serverBytesPerSecond() < 0 ? "unavailable" : String.format(java.util.Locale.ROOT, "%.2f Mbps", status.serverBytesPerSecond() * 8D / 1_000_000D),
                    status.admissionBlocked() ? "blocked" : "open"));
            sender.sendMessage("Muted players (" + status.mutedPlayers().size() + "): " + status.mutedPlayers().stream()
                    .map(uuid -> { Player player = org.bukkit.Bukkit.getPlayer(uuid); return player == null ? uuid.toString() : player.getName(); }).limit(20).collect(Collectors.joining(", ")));
            return true;
        }
        if (args.length < 2) { sender.sendMessage("Usage: /voicechat " + args[0] + " <player>"); return true; }
        Player target = parsePlayer(sender, args[1]);
        if (target == null) { sender.sendMessage("Player not found."); return true; }
        try {
            boolean mute = args[0].equalsIgnoreCase("mute");
            if (mute) server.getProtectionManager().mute(target.getUniqueId()); else server.getProtectionManager().unmute(target.getUniqueId());
            sender.sendMessage((mute ? "Muted " : "Unmuted ") + target.getName() + " for voice chat.");
        } catch (Exception e) { sender.sendMessage("Could not update voice mute: " + e.getMessage()); }
        return true;
    }

    private boolean reportCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player reporter)) { sender.sendMessage("Only a player can report voice chat."); return true; }
        if (args.length < 2) { sender.sendMessage("Usage: /voicechat report <player> [reason]"); return true; }
        Player target = parsePlayer(sender, args[1]);
        if (target == null) { sender.sendMessage("Player not found."); return true; }
        String reason = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : "player_report";
        Server server = Voicechat.SERVER.getServer();
        if (server == null || !server.getModerationCache().report(target.getUniqueId(), reporter.getUniqueId(), reason)) {
            sender.sendMessage("No recent cached voice audio is available, or voice moderation caching is disabled.");
            return true;
        }
        sender.sendMessage("Voice report queued for " + target.getName() + ".");
        return true;
    }

    private List<String> tabCompleteList(String arg, List<String> list) {
        return list.stream().filter(s -> s.startsWith(arg)).collect(Collectors.toList());
    }

    private boolean helpCommand(Player commandSender, Command command, String label, String[] args) {
        commandSender.sendMessage("/voicechat [help]");
        commandSender.sendMessage("/voicechat [test] <target>");
        commandSender.sendMessage("/voicechat [invite] <target>");
        commandSender.sendMessage("/voicechat [join] <group> [<password>]");
        commandSender.sendMessage("/voicechat [leave]");
        return true;
    }

    private boolean testCommand(Player commandSender, Command command, String label, String[] args) {
        if (args.length < 2) {
            return false;
        }

        Player player = parsePlayer(commandSender, args[1]);

        if (player == null) {
            NetManager.sendMessage(commandSender, "argument.entity.notfound.player");
            return true;
        }

        if (!Voicechat.SERVER.isCompatible(player)) {
            NetManager.sendMessage(commandSender, "message.voicechat.player_no_voicechat", player.getDisplayName(), BuildConstants.PLUGIN_NAME);
            return true;
        }

        ClientConnection clientConnection = Voicechat.SERVER.getServer().getConnections().get(player.getUniqueId());

        if (clientConnection == null) {
            NetManager.sendMessage(commandSender, "message.voicechat.client_not_connected");
            return true;
        }

        try {
            NetManager.sendMessage(commandSender, "message.voicechat.sending_ping");
            Voicechat.SERVER.getServer().getPingManager().sendPing(clientConnection, 500, 10, new PingManager.PingListener() {

                @Override
                public void onPong(int attempts, long pingMilliseconds) {
                    if (attempts <= 1) {
                        NetManager.sendMessage(commandSender, "message.voicechat.ping_received", String.valueOf(pingMilliseconds));
                    } else {
                        NetManager.sendMessage(commandSender, "message.voicechat.ping_received_attempt", String.valueOf(pingMilliseconds), String.valueOf(attempts));
                    }
                }

                @Override
                public void onFailedAttempt(int attempts) {
                    NetManager.sendMessage(commandSender, "message.voicechat.ping_retry");
                }

                @Override
                public void onTimeout(int attempts) {
                    NetManager.sendMessage(commandSender, "message.voicechat.ping_timed_out", String.valueOf(attempts));
                }
            });
            NetManager.sendMessage(commandSender, "message.voicechat.ping_sent_waiting");
        } catch (Exception e) {
            NetManager.sendMessage(commandSender, "message.voicechat.failed_to_send_ping", e.getMessage());
            Voicechat.LOGGER.warn("Failed to send ping", e);
        }
        return true;
    }

    private boolean inviteCommand(Player commandSender, Command command, String label, String[] args) {
        if (args.length < 2) {
            return false;
        }

        Player player = parsePlayer(commandSender, args[1]);

        if (player == null || !Voicechat.compatibility.canSee(commandSender, player)) {
            NetManager.sendMessage(commandSender, "argument.entity.notfound.player");
            return true;
        }

        PlayerState state = Voicechat.SERVER.getServer().getPlayerStateManager().getState(commandSender.getUniqueId());

        if (state == null || !state.hasGroup()) {
            NetManager.sendMessage(commandSender, "message.voicechat.not_in_group");
            return true;
        }

        Group group = Voicechat.SERVER.getServer().getGroupManager().getGroup(state.getGroup());
        if (group == null) {
            return true;
        }

        if (!Voicechat.SERVER.isCompatible(player)) {
            NetManager.sendMessage(commandSender, "message.voicechat.player_no_voicechat", player.getDisplayName(), BuildConstants.PLUGIN_NAME);
            return true;
        }

        String passwordSuffix = group.getPassword() == null ? "" : " \"" + group.getPassword() + "\"";
        String cmd = "/voicechat join " + group.getId().toString() + passwordSuffix;
        Voicechat.compatibility.sendInviteMessage(player, commandSender, group.getName(), cmd);

        NetManager.sendMessage(commandSender, "message.voicechat.invite_successful", player.getName());

        return true;
    }

    private boolean joinCommand(Player commandSender, Command command, String label, String[] args) {
        if (args.length < 2) {
            return false;
        }

        int argIndex = 1;
        UUID groupUUID;
        try {
            groupUUID = UUID.fromString(args[argIndex]);
        } catch (Exception e) {
            String groupName;
            if (args[argIndex].startsWith("\"")) {
                StringBuilder sb = new StringBuilder();
                for (; argIndex < args.length; argIndex++) {
                    sb.append(args[argIndex]).append(" ");
                    if (args[argIndex].endsWith("\"") && !args[argIndex].endsWith("\\\"")) {
                        break;
                    }
                }
                groupName = sb.toString().trim();
                String[] split = groupName.split("\"");
                if (split.length > 1) {
                    groupName = split[1];
                }
            } else {
                groupName = args[argIndex];
            }
            groupUUID = getGroupUUID(commandSender, Voicechat.SERVER.getServer(), groupName);
        }

        if (groupUUID == null) {
            return true;
        }

        argIndex++;

        String password = null;
        if (args.length >= argIndex + 1) {
            StringBuilder sb = new StringBuilder();
            for (; argIndex < args.length; argIndex++) {
                sb.append(args[argIndex]).append(" ");
            }
            password = sb.toString().trim();
            if (password.startsWith("\"")) {
                String[] split = password.split("\"");
                if (split.length > 1) {
                    password = split[1];
                }
            }
        }

        joinGroup(commandSender, groupUUID, password);
        return true;
    }

    private UUID getGroupUUID(Player commandSender, Server server, String groupName) {
        List<Group> groups = server.getGroupManager().getGroups().values().stream().filter(group -> group.getName().equals(groupName)).collect(Collectors.toList());

        if (groups.isEmpty()) {
            NetManager.sendMessage(commandSender, "message.voicechat.group_does_not_exist");
            return null;
        }

        if (groups.size() > 1) {
            NetManager.sendMessage(commandSender, "message.voicechat.group_name_not_unique");
            return null;
        }

        return groups.get(0).getId();
    }

    private static void joinGroup(Player commandSender, UUID groupID, @Nullable String password) {
        if (!Voicechat.SERVER_CONFIG.groupsEnabled.get()) {
            NetManager.sendMessage(commandSender, "message.voicechat.groups_disabled");
            return;
        }

        Server server = Voicechat.SERVER.getServer();

        if (!commandSender.hasPermission(PermissionManager.GROUPS_PERMISSION)) {
            NetManager.sendMessage(commandSender, "message.voicechat.no_group_permission");
            return;
        }

        Group group = server.getGroupManager().getGroup(groupID);

        if (group == null) {
            NetManager.sendMessage(commandSender, "message.voicechat.group_does_not_exist");
            return;
        }

        server.getGroupManager().joinGroup(group, commandSender, password);
        NetManager.sendMessage(commandSender, "message.voicechat.join_successful", group.getName()); //TODO Make group name green
    }

    private boolean leaveCommand(Player commandSender, Command command, String label, String[] args) {
        if (!Voicechat.SERVER_CONFIG.groupsEnabled.get()) {
            NetManager.sendMessage(commandSender, "message.voicechat.groups_disabled");
            return true;
        }

        Server server = Voicechat.SERVER.getServer();
        PlayerState state = server.getPlayerStateManager().getState(commandSender.getUniqueId());
        if (state == null || !state.hasGroup()) {
            NetManager.sendMessage(commandSender, "message.voicechat.not_in_group");
            return true;
        }

        server.getGroupManager().leaveGroup(commandSender);
        NetManager.sendMessage(commandSender, "message.voicechat.leave_successful");
        return true;
    }

    private static boolean checkNoVoicechat(CommandSender commandSender) {
        if (commandSender instanceof Player) {
            if (Voicechat.SERVER.isCompatible((Player) commandSender)) {
                return false;
            }
            commandSender.sendMessage(String.format(Voicechat.TRANSLATIONS.voicechatNeededForCommandMessage.get(), "Simple Voice Chat"));
        } else {
            commandSender.sendMessage(Voicechat.TRANSLATIONS.playerCommandMessage.get());
        }
        return true;
    }

    @Nullable
    public static Player parsePlayer(CommandSender commandSender, String playerArg) {
        Player player = commandSender.getServer().getPlayer(playerArg);
        if (player != null) {
            return player;
        }
        try {
            UUID uuid = UUID.fromString(playerArg);
            return commandSender.getServer().getPlayer(uuid);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
