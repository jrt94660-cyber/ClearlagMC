package com.flovex.clearlag;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.regex.Matcher;

public final class ClearlagMC extends JavaPlugin {
    private boolean pluginEnabled, timerEnabled, entitiesEnabled;
    private int intervalSeconds, secondsRemaining, timerTaskId = -1;
    private List<Integer> warningSeconds = new ArrayList<>();
    private List<String> protectedTypes = new ArrayList<>(), protectedProjectileTypes = new ArrayList<>(), protectedMetadata = new ArrayList<>();
    private List<Pattern> protectedNamePatterns = new ArrayList<>();

    @Override public void onEnable() {
        saveDefaultConfig();
        saveResource("messages.yml", false);
        saveResource("settings/timer.yml", false);
        saveResource("settings/entities.yml", false);
        saveResource("settings/protections.yml", false);
        loadAll();
        if (!pluginEnabled) { getLogger().info("Disabled by config.yml (plugin.enabled=false)."); return; }
        if (getCommand("clearlag") != null) getCommand("clearlag").setExecutor(this);
        if (timerEnabled) startTimer();
        getLogger().info("ClearlagMC enabled. Entity removal=" + entitiesEnabled + ", timer=" + timerEnabled + ". No startup cleanup performed.");
    }
    @Override public void onDisable() { stopTimer(); }

    private void loadAll() {
        reloadConfig();
        pluginEnabled = getConfig().getBoolean("plugin.enabled", true);
        ensureFile("messages.yml"); ensureFile("settings/timer.yml"); ensureFile("settings/entities.yml"); ensureFile("settings/protections.yml");
        timerEnabled = yaml("settings/timer.yml").getBoolean("timer.enabled", true);
        intervalSeconds = Math.max(1, yaml("settings/timer.yml").getInt("timer.interval-seconds", 600));
        warningSeconds = yaml("settings/timer.yml").getIntegerList("timer.warnings-seconds");
        warningSeconds.sort(Collections.reverseOrder());
        entitiesEnabled = yaml("settings/entities.yml").getBoolean("entities.enabled", false);
        var p = yaml("settings/protections.yml");
        protectedTypes = upperList(p.getStringList("protections.entity-types"));
        protectedProjectileTypes = upperList(p.getStringList("protections.protect-projectiles"));
        protectedMetadata = p.getStringList("protections.protect-entities-with-metadata");
        protectedNamePatterns = new ArrayList<>();
        for (String regex : p.getStringList("protections.protect-custom-name-patterns")) {
            try { protectedNamePatterns.add(Pattern.compile(regex)); }
            catch (PatternSyntaxException ex) { getLogger().warning("Invalid protected-name regex: " + regex); }
        }
        secondsRemaining = intervalSeconds;
    }
    private void ensureFile(String path) { if (!Files.exists(getDataFolder().toPath().resolve(path))) saveResource(path, false); }
    private org.bukkit.configuration.file.YamlConfiguration yaml(String path) { return org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(getDataFolder().toPath().resolve(path).toFile()); }
    private List<String> upperList(List<String> list) { return list.stream().map(s -> s.toUpperCase(Locale.ROOT)).toList(); }

    private void startTimer() {
        stopTimer(); secondsRemaining = intervalSeconds;
        timerTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
            if (!pluginEnabled || !timerEnabled) return;
            if (secondsRemaining <= 0) { performCleanup(true); secondsRemaining = intervalSeconds; return; }
            if (warningSeconds.contains(secondsRemaining)) {
                String key = entitiesEnabled ? "messages.warning" : "messages.warning-disabled";
                broadcast(message(key).replace("%seconds%", String.valueOf(secondsRemaining)));
            }
            secondsRemaining--;
        }, 0L, 20L);
    }
    private void stopTimer() { if (timerTaskId != -1) Bukkit.getScheduler().cancelTask(timerTaskId); timerTaskId = -1; }
    private String timeText(int seconds) { int m=seconds/60,s=seconds%60; return (m>0?m+"m ":"")+s+"s"; }

    private CleanupResult performCleanup(boolean broadcastResult) {
        if (!entitiesEnabled) { if (broadcastResult) getLogger().info("Scheduled clear skipped: entities disabled."); return new CleanupResult(0,0); }
        int removed=0, skipped=0;
        for (var world:Bukkit.getWorlds()) for (Entity entity:world.getEntities()) {
            if (entity instanceof Player) continue;
            if (isProtected(entity)) { skipped++; continue; }
            entity.remove(); removed++;
        }
        logCleanup(removed,skipped);
        if (broadcastResult) broadcast(message("messages.clear-broadcast").replace("%removed%",String.valueOf(removed)));
        return new CleanupResult(removed,skipped);
    }
    private boolean isProtected(Entity entity) {
        String type=entity.getType().name();
        if (entity instanceof LivingEntity) return true; // Never remove living entities.
        if (protectedTypes.contains(type)||protectedProjectileTypes.contains(type)) return true;
        // Hard protections cannot be disabled in configuration.
        if (type.equals("ENDER_PEARL")||type.contains("WIND_CHARGE")||type.endsWith("ARROW")||type.equals("TRIDENT")||type.endsWith("_DISPLAY")||type.contains("NPC")||type.equals("ARMOR_STAND")) return true;
        if (hasMetadata(entity,"NPC")||hasMetadata(entity,"hologram")||hasMetadata(entity,"Hologram")||hasMetadata(entity,"CitizensNPC")) return true;
        for (String key:protectedMetadata) if (hasMetadata(entity,key)) return true;
        if (entity.getCustomName()!=null) {
            String name=ChatColor.stripColor(entity.getCustomName());
            for (Pattern pattern:protectedNamePatterns) if (pattern.matcher(name).find()) return true;
            String lower=name.toLowerCase(Locale.ROOT);
            if (lower.contains("npc")||lower.contains("hologram")) return true;
        }
        return false;
    }
    private boolean hasMetadata(Entity entity,String key) {
        if (!entity.hasMetadata(key)) return false;
        for (MetadataValue v:entity.getMetadata(key)) if (v.getOwningPlugin()!=null) return true;
        return false;
    }
    private void logCleanup(int removed,int skipped) {
        try {
            var dir=getDataFolder().toPath().resolve("logs"); Files.createDirectories(dir);
            var file=dir.resolve(LocalDate.now()+".log");
            Files.writeString(file,"["+java.time.LocalTime.now()+"] removed="+removed+" protected/skipped="+skipped+System.lineSeparator(),StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        } catch(IOException e) { getLogger().warning("Log write failed: "+e.getMessage()); }
    }
    private String message(String key) { return color(yaml("messages.yml").getString(key,key)); }
    private String color(String s) {
        Matcher m=Pattern.compile("&#([A-Fa-f0-9]{6})").matcher(s); StringBuffer out=new StringBuffer();
        while(m.find()){ String hex=m.group(1); StringBuilder legacy=new StringBuilder("§x"); for(char c:hex.toCharArray()) legacy.append('§').append(c); m.appendReplacement(out,Matcher.quoteReplacement(legacy.toString()); }
        m.appendTail(out); return ChatColor.translateAlternateColorCodes('&',out.toString());
    }
    private void send(CommandSender s,String text){s.sendMessage(message("messages.prefix")+text);}
    private void broadcast(String text){Bukkit.getServer().broadcastMessage(message("messages.prefix")+text);}
    private boolean admin(CommandSender s){if(s.hasPermission("clearlag.admin"))return true;send(s,message("messages.no-permission"));return false;}

    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        if(!pluginEnabled){send(sender,message("messages.disabled"));return true;}
        if(args.length==0){if(!sender.hasPermission("clearlag.command")){send(sender,message("messages.no-permission"));return true;}send(sender,message("messages.usage"));return true;}
        switch(args[0].toLowerCase(Locale.ROOT)){
            case "timer" -> {
                if(args.length==1){if(!sender.hasPermission("clearlag.command")){send(sender,message("messages.no-permission"));return true;}String st=timerEnabled?"enabled":"disabled";send(sender,message("messages.timer-status").replace("%status%",st).replace("%time%",timerEnabled?timeText(secondsRemaining):"paused"));return true;}
                if(!admin(sender))return true;
                if(args[1].equalsIgnoreCase("on")){timerEnabled=true;yaml("settings/timer.yml").set("timer.enabled",true);saveYaml("settings/timer.yml");startTimer();send(sender,message("messages.timer-on"));}
                else if(args[1].equalsIgnoreCase("off")){timerEnabled=false;yaml("settings/timer.yml").set("timer.enabled",false);saveYaml("settings/timer.yml");stopTimer();send(sender,message("messages.timer-off"));}
                else send(sender,message("messages.timer-usage"));return true;
            }
            case "clear" -> {if(!admin(sender))return true;if(!entitiesEnabled){send(sender,message("messages.clear-disabled"));return true;}send(sender,message("messages.clear-start"));CleanupResult r=performCleanup(false);send(sender,message("messages.clear-result").replace("%removed%",String.valueOf(r.removed)).replace("%skipped%",String.valueOf(r.skipped)));return true;}
            case "reload" -> {if(!admin(sender))return true;stopTimer();loadAll();if(pluginEnabled&&timerEnabled)startTimer();send(sender,message("messages.reload"));return true;}
            case "entities" -> {if(!admin(sender))return true;if(args.length<2){send(sender,message("messages.usage"));return true;}if(args[1].equalsIgnoreCase("on")){entitiesEnabled=true;yaml("settings/entities.yml").set("entities.enabled",true);saveYaml("settings/entities.yml");send(sender,message("messages.entity-on"));}else if(args[1].equalsIgnoreCase("off")){entitiesEnabled=false;yaml("settings/entities.yml").set("entities.enabled",false);saveYaml("settings/entities.yml");send(sender,message("messages.entity-off"));}else send(sender,message("messages.usage"));return true;}
            case "toggle" -> {if(!admin(sender))return true;entitiesEnabled=!entitiesEnabled;var y=yaml("settings/entities.yml");y.set("entities.enabled",entitiesEnabled);saveYaml("settings/entities.yml");send(sender,entitiesEnabled?message("messages.entity-on"):message("messages.entity-off"));return true;}
            default -> {send(sender,message("messages.usage"));return true;}
        }
    }
    private void saveYaml(String path){try{yaml(path).save(getDataFolder().toPath().resolve(path).toFile());}catch(IOException e){getLogger().warning("Save failed for "+path+": "+e.getMessage());}}
    private record CleanupResult(int removed,int skipped){}
}