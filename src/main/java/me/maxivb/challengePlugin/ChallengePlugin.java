package me.maxivb.challengePlugin;

import org.bukkit.*;
import org.bukkit.advancement.*;
import org.bukkit.boss.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.*;

import java.util.*;

public class ChallengePlugin extends JavaPlugin implements Listener {

    private boolean challengeActive = false;

    private final Map<String, Set<UUID>> teams = new HashMap<>();
    private final Map<UUID, String> playerTeam = new HashMap<>();
    private final Map<UUID, UUID> invites = new HashMap<>();
    private final Map<String, BossBar> teamBars = new HashMap<>();
    private final Set<UUID> resetConfirm = new HashSet<>();
    private final Map<UUID, String> deleteConfirm = new HashMap<>();

    private FileConfiguration config;
    private Scoreboard scoreboard;
    private UUID getTeamOwner(String team) {
        Set<UUID> members = teams.get(team);
        if (members == null || members.isEmpty()) return null;
        return members.iterator().next();
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        config = getConfig();

        scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();

        loadTeams();

        Bukkit.getPluginManager().registerEvents(this, this);

        Bukkit.getOnlinePlayers().forEach(this::applyTeamData);
    }

    @Override
    public void onDisable() {
        saveTeams();
    }

    // ================= COMMAND =================
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {

        if (!(sender instanceof Player player)) return true;
        if (!cmd.getName().equalsIgnoreCase("challenge")) return true;
        if (args.length == 0) return true;

        // ================= RESET =================
        if (args[0].equalsIgnoreCase("reset")) {

            if (!player.hasPermission("challenge.admin")) {
                player.sendMessage("§cNur Admins können resetten.");
                return true;
            }

            // CONFIRM STEP
            if (args.length >= 2 && args[1].equalsIgnoreCase("confirm")) {

                if (!resetConfirm.contains(player.getUniqueId())) {
                    player.sendMessage("§cKeine Reset-Bestätigung aktiv!");
                    return true;
                }

                resetConfirm.remove(player.getUniqueId());
                resetAll();

                Bukkit.broadcastMessage("§cChallenge wurde vollständig zurückgesetzt!");
                return true;
            }

            resetConfirm.add(player.getUniqueId());

            player.sendMessage("§cWARNUNG: Reset löscht ALLES!");
            player.sendMessage("§7Tippe §e/challenge reset confirm §7zur Bestätigung.");

            Bukkit.getScheduler().runTaskLater(this, () -> {
                resetConfirm.remove(player.getUniqueId());
            }, 200L);

            return true;
        }

        // ================= START / STOP =================
        if (args[0].equalsIgnoreCase("All_Achievements")) {

            if (args.length < 2) return true;

            if (args[1].equalsIgnoreCase("start")) {
                challengeActive = true;
                Bukkit.broadcastMessage("§aChallenge gestartet!");
                updateAllBossBars();
            }

            if (args[1].equalsIgnoreCase("stop")) {
                challengeActive = false;
                Bukkit.broadcastMessage("§cChallenge gestoppt!");
                teamBars.values().forEach(bar -> bar.setVisible(false));
            }

            return true;
        }

        // ================= TEAM =================
        if (args[0].equalsIgnoreCase("team")) {

            if (args.length < 2) return true;

            if (args[1].equalsIgnoreCase("delete")) {

                String team = playerTeam.get(player.getUniqueId());

                if (team == null) {
                    player.sendMessage("§cDu bist in keinem Team!");
                    return true;
                }

                UUID owner = getTeamOwner(team);

                boolean isOwner = owner != null && owner.equals(player.getUniqueId());
                boolean hasPerm = player.hasPermission("challenge.admin");

                // CONFIRM STEP
                if (args.length >= 3 && args[2].equalsIgnoreCase("confirm")) {

                    if (!deleteConfirm.containsKey(player.getUniqueId())) {
                        player.sendMessage("§cKeine Lösch-Bestätigung aktiv!");
                        return true;
                    }

                    String confirmTeam = deleteConfirm.remove(player.getUniqueId());

                    if (!confirmTeam.equals(team)) {
                        player.sendMessage("§cTeam hat sich geändert!");
                        return true;
                    }

                    deleteTeam(confirmTeam);

                    Bukkit.broadcastMessage("§cTeam " + confirmTeam + " wurde gelöscht!");
                    return true;
                }

                // permission check
                if (!isOwner && !hasPerm) {
                    player.sendMessage("§cNur der Owner oder Admins können das Team löschen!");
                    return true;
                }

                // START CONFIRMATION
                deleteConfirm.put(player.getUniqueId(), team);

                player.sendMessage("§cWARNUNG: Team wird gelöscht!");
                player.sendMessage("§7Bestätige mit: §e/challenge team delete confirm");

                Bukkit.getScheduler().runTaskLater(this, () -> {
                    deleteConfirm.remove(player.getUniqueId());
                }, 200L);

                return true;
            }

            // LIST
            if (args[1].equalsIgnoreCase("list")) {
                player.sendMessage("§6Teams:");
                for (String team : teams.keySet()) {
                    player.sendMessage("§e- " + team + " §7(" + teams.get(team).size() + ")");
                }
                return true;
            }

            // INFO
            if (args[1].equalsIgnoreCase("info")) {

                String team = playerTeam.get(player.getUniqueId());

                if (team == null) {
                    player.sendMessage("§cDu bist in keinem Team!");
                    return true;
                }

                Set<UUID> members = teams.get(team);

                player.sendMessage("§6========== TEAM INFO ==========");
                player.sendMessage("§eName: §f" + team);
                player.sendMessage("§eStatus: " + (challengeActive ? "§aAktiv" : "§cInaktiv"));

                player.sendMessage("§eMitglieder:");
                for (UUID uuid : members) {
                    OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
                    player.sendMessage(" §7- " + op.getName());
                }

                player.sendMessage("§eFortschritt: §a" + getTeamProgress(team) + "§7/§f" + countAdvancements());
                player.sendMessage("§6==============================");

                return true;
            }

            // CREATE
            if (args[1].equalsIgnoreCase("create") && args.length >= 3) {

                if (playerTeam.containsKey(player.getUniqueId())) {
                    player.sendMessage("§cDu bist bereits in einem Team!");
                    return true;
                }

                String name = args[2];

                teams.put(name, new HashSet<>());
                teams.get(name).add(player.getUniqueId());
                playerTeam.put(player.getUniqueId(), name);

                createTeamVisual(name);
                applyTeamData(player);

                saveTeams();

                player.sendMessage("§aTeam erstellt: " + name);
                return true;
            }

            // INVITE
            if (args[1].equalsIgnoreCase("invite") && args.length >= 3) {
                Player target = Bukkit.getPlayer(args[2]);
                if (target == null) return true;

                invites.put(target.getUniqueId(), player.getUniqueId());
                target.sendMessage("§eEinladung erhalten! /challenge team join");
                return true;
            }

            // JOIN
            if (args[1].equalsIgnoreCase("join")) {

                if (playerTeam.containsKey(player.getUniqueId())) {
                    player.sendMessage("§cDu bist bereits in einem Team!");
                    return true;
                }

                if (!invites.containsKey(player.getUniqueId())) return true;

                UUID inviter = invites.remove(player.getUniqueId());
                String team = playerTeam.get(inviter);

                teams.get(team).add(player.getUniqueId());
                playerTeam.put(player.getUniqueId(), team);

                applyTeamData(player);
                syncTeam(team);

                saveTeams();

                player.sendMessage("§aTeam beigetreten: " + team);
                return true;
            }

            // LEAVE
            if (args[1].equalsIgnoreCase("leave")) {
                leaveTeam(player);
                return true;
            }

            // KICK
            if (args[1].equalsIgnoreCase("kick") && args.length >= 3) {
                Player target = Bukkit.getPlayer(args[2]);
                if (target == null) return true;

                String team = playerTeam.get(player.getUniqueId());
                if (team == null) return true;

                if (!team.equals(playerTeam.get(target.getUniqueId()))) {
                    player.sendMessage("§cNicht im selben Team!");
                    return true;
                }

                leaveTeam(target);

                player.sendMessage("§cGekickt!");
                target.sendMessage("§cDu wurdest gekickt!");
                return true;
            }
        }

        return true;
    }

    // ================= ADVANCEMENT =================
    @EventHandler
    public void onAdv(PlayerAdvancementDoneEvent e) {
        if (!challengeActive) return;

        Player player = e.getPlayer();
        String team = playerTeam.get(player.getUniqueId());
        if (team == null) return;

        Advancement adv = e.getAdvancement();

        for (UUID uuid : teams.get(team)) {
            Player target = Bukkit.getPlayer(uuid);
            if (target != null) {
                grantAdv(target, adv);
            }
        }

        updateBossBar(team);
        checkWin(team);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        applyTeamData(e.getPlayer());
    }

    // ================= SYNC =================
    private void syncTeam(String team) {
        Set<UUID> members = teams.get(team);

        for (UUID u1 : members) {
            Player p1 = Bukkit.getPlayer(u1);
            if (p1 == null) continue;

            for (UUID u2 : members) {
                Player p2 = Bukkit.getPlayer(u2);
                if (p2 == null) continue;

                syncPlayer(p1, p2);
            }
        }

        updateBossBar(team);
        checkWin(team);
    }

    private void syncPlayer(Player source, Player target) {
        Iterator<Advancement> it = Bukkit.advancementIterator();

        while (it.hasNext()) {
            Advancement adv = it.next();

            if (adv.getKey().getKey().contains("recipes")) continue;

            if (source.getAdvancementProgress(adv).isDone()) {
                grantAdv(target, adv);
            }
        }
    }

    private void grantAdv(Player player, Advancement adv) {
        AdvancementProgress prog = player.getAdvancementProgress(adv);
        for (String c : prog.getRemainingCriteria()) {
            prog.awardCriteria(c);
        }
    }

    // ================= WIN =================
    private void checkWin(String team) {
        int total = countAdvancements();
        int progress = getTeamProgress(team);

        if (progress >= total && challengeActive) {

            Bukkit.broadcastMessage("§6§lTEAM " + team + " HAT GEWONNEN!");

            challengeActive = false;

            teamBars.values().forEach(bar -> bar.setVisible(false));

            for (Player p : Bukkit.getOnlinePlayers()) {
                p.teleport(p.getWorld().getSpawnLocation());
            }
        }
    }

    // ================= BOSSBAR =================
    private void updateBossBar(String team) {
        if (!challengeActive) return;

        BossBar bar = teamBars.get(team);
        if (bar == null) return;

        int total = countAdvancements();
        int progress = getTeamProgress(team);

        bar.setTitle("§aAdvancements: " + progress + "/" + total);
        bar.setProgress(Math.min(1.0, progress / (double) total));
        bar.setVisible(true);
    }

    private void updateAllBossBars() {
        for (String team : teams.keySet()) {
            updateBossBar(team);
        }
    }

    private int getTeamProgress(String team) {
        int count = 0;

        Iterator<Advancement> it = Bukkit.advancementIterator();

        while (it.hasNext()) {
            Advancement adv = it.next();

            if (adv.getKey().getKey().contains("recipes")) continue;

            for (UUID uuid : teams.get(team)) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null && p.getAdvancementProgress(adv).isDone()) {
                    count++;
                    break;
                }
            }
        }

        return count;
    }

    private int countAdvancements() {
        int count = 0;

        Iterator<Advancement> it = Bukkit.advancementIterator();
        while (it.hasNext()) {
            Advancement adv = it.next();
            if (!adv.getKey().getKey().contains("recipes")) count++;
        }

        return count;
    }

    // ================= TEAM =================
    private void createTeamVisual(String name) {
        Team team = scoreboard.getTeam(name);
        if (team == null) team = scoreboard.registerNewTeam(name);

        team.setPrefix("§8[§d" + name + "§8] ");

        BossBar bar = Bukkit.createBossBar("", BarColor.GREEN, BarStyle.SOLID);
        teamBars.put(name, bar);
    }

    private void applyTeamData(Player player) {
        String team = playerTeam.get(player.getUniqueId());
        if (team == null) return;

        createTeamVisual(team);

        Team sb = scoreboard.getTeam(team);
        sb.addEntry(player.getName());

        player.setScoreboard(scoreboard);
        teamBars.get(team).addPlayer(player);

        updateBossBar(team);
    }

    private void removeTag(Player player) {
        for (Team t : scoreboard.getTeams()) {
            t.removeEntry(player.getName());
        }
    }

    private void leaveTeam(Player player) {
        String team = playerTeam.remove(player.getUniqueId());
        if (team == null) return;

        teams.get(team).remove(player.getUniqueId());
        removeTag(player);
        saveTeams();
    }

    private void deleteTeam(String team) {

        Set<UUID> members = teams.remove(team);
        if (members == null) return;

        for (UUID uuid : members) {
            playerTeam.remove(uuid);

            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                removeTag(p);
                p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            }
        }

        BossBar bar = teamBars.remove(team);
        if (bar != null) bar.removeAll();

        Team sb = scoreboard.getTeam(team);
        if (sb != null) sb.unregister();

        saveTeams();
    }

    // ================= SAVE =================
    private void saveTeams() {
        config.set("teams", null);

        for (String team : teams.keySet()) {
            List<String> list = teams.get(team).stream().map(UUID::toString).toList();
            config.set("teams." + team, list);
        }

        saveConfig();
    }

    private void loadTeams() {
        if (!config.contains("teams")) return;

        for (String team : config.getConfigurationSection("teams").getKeys(false)) {
            List<String> list = config.getStringList("teams." + team);

            Set<UUID> members = new HashSet<>();
            for (String s : list) {
                UUID uuid = UUID.fromString(s);
                members.add(uuid);
                playerTeam.put(uuid, team);
            }

            teams.put(team, members);
            createTeamVisual(team);
        }
    }
    private void resetAll() {
        challengeActive = false;

        // hide bossbars
        for (BossBar bar : teamBars.values()) {
            bar.setVisible(false);
        }

        // clear teams
        teams.clear();
        playerTeam.clear();
        invites.clear();

        // clear scoreboard teams
        for (Team t : scoreboard.getTeams()) {
            t.unregister();
        }

        // reset players
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.getInventory().clear();
            p.teleport(p.getWorld().getSpawnLocation());
            p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }

        saveTeams();
    }
}