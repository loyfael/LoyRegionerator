package com.github.jikoo.regionerator;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import com.google.common.collect.ImmutableList;

/**
 * Plugin for deleting unused region files gradually.
 * 
 * @author Jikoo
 */
public class Regionerator extends JavaPlugin {

	private long flagDuration;
	private long ticksPerFlag;
	private long ticksPerFlagAutosave;
	private List<String> worlds;
	private List<Hook> protectionHooks;
	private ChunkFlagger chunkFlagger;
	private HashMap<String, DeletionRunnable> deletionRunnables;
	private long millisBetweenCycles;
	private DebugLevel debugLevel;
	private boolean paused;

	@Override
	public void onEnable() {

		saveDefaultConfig();

		paused = false;

		List<String> worldList = getConfig().getStringList("worlds");
		if (worldList.isEmpty()) {
			getLogger().severe("No worlds are enabled. Disabling!");
			getServer().getPluginManager().disablePlugin(this);
			return;
		}

		boolean dirtyConfig = false;

		try {
			debugLevel = DebugLevel.valueOf(getConfig().getString("debug-level", "OFF").toUpperCase());
		} catch (IllegalArgumentException e) {
			debugLevel = DebugLevel.OFF;
			getConfig().set("debug-level", "OFF");
			dirtyConfig = true;
		}
		if (debug(DebugLevel.LOW)) {
			debug("Debug level: " + debugLevel.name());
		}

		worlds = new ArrayList<>();
		for (World world : Bukkit.getWorlds()) {
			if (worldList.contains(world.getName())) {
				worlds.add(world.getName());
				continue;
			}
			for (String name : worldList) {
				if (world.getName().equalsIgnoreCase(name)) {
					worlds.add(world.getName());
					dirtyConfig = true;
					break;
				}
			}
		}
		// Immutable list, this should not be changed during run by myself or another plugin
		worlds = ImmutableList.copyOf(worlds);
		if (dirtyConfig) {
			getConfig().set("worlds", worlds);
		}

		if (getConfig().getInt("days-till-flag-expires") < 1) {
			getConfig().set("days-till-flag-expires", 1);
			dirtyConfig = true;
		}

		// 86,400,000 = 24 hours * 60 minutes * 60 seconds * 1000 milliseconds
		flagDuration = 86400000L * getConfig().getInt("days-till-flag-expires");

		for (String worldName : worlds) {
			if (getConfig().getLong("delete-this-to-reset-plugin." + worldName, 0) == 0) {
				// Set time to start actually deleting chunks to ensure that all existing areas are given a chance
				getConfig().set("delete-this-to-reset-plugin." + worldName, System.currentTimeMillis() + flagDuration);
				dirtyConfig = true;
			}
		}

		if (getConfig().getInt("chunk-flag-radius") < 0) {
			getConfig().set("chunk-flag-radius", 4);
			dirtyConfig = true;
		}

		if (getConfig().getInt("seconds-per-flag") < 1) {
			getConfig().set("seconds-per-flag", 10);
			dirtyConfig = true;
		}
		// 20 ticks per second
		ticksPerFlag = getConfig().getInt("seconds-per-flag") * 20L;

		if (getConfig().getInt("minutes-per-flag-autosave") < 1) {
			getConfig().set("minutes-per-flag-autosave", 5);
			dirtyConfig = true;
		}
		// 60 seconds per minute, 20 ticks per second
		ticksPerFlagAutosave = getConfig().getInt("seconds-per-flag") * 120L;

		if (getConfig().getLong("ticks-per-deletion") < 1) {
			getConfig().set("ticks-per-deletion", 20L);
			dirtyConfig = true;
		}

		if (getConfig().getInt("regions-per-deletion") < 1) {
			getConfig().set("regions-per-deletion", 1);
			dirtyConfig = true;
		}

		if (getConfig().getInt("hours-between-cycles") < 0) {
			getConfig().set("hours-between-cycles", 0);
		}
		// 60 minutes per hour, 60 seconds per minute, 1000 milliseconds per second
		millisBetweenCycles = getConfig().getInt("hours-between-cycles") * 360000L;

		protectionHooks = new ArrayList<>();
		for (String pluginName : getConfig().getConfigurationSection("hooks").getKeys(false)) {
			try {
				Class<?> clazz = Class.forName("com.github.jikoo.regionerator.hooks." + pluginName + "Hook");
				if (!clazz.isAssignableFrom(Hook.class)) {
					// What.
					continue;
				}
				Hook hook = (Hook) clazz.newInstance();
				if (hook.isHookUsable()) {
					protectionHooks.add(hook);
					if (debug(DebugLevel.LOW)) {
						debug("Enabled protection hook for " + pluginName);
					}
				}
			} catch (ClassNotFoundException e) {
				getLogger().severe("No hook found for " + pluginName + "! Please request compatibility!");
			} catch (InstantiationException | IllegalAccessException e) {
				getLogger().severe("Unable to enable hook for " + pluginName + "!");
				e.printStackTrace();
			}
		}

		if (dirtyConfig) {
			saveConfig();
		}

		chunkFlagger = new ChunkFlagger(this);
		chunkFlagger.scheduleSaving();

		deletionRunnables = new HashMap<>();

		new FlaggingRunnable(this).runTaskTimer(this, 0, getTicksPerFlag());

		if (debug(DebugLevel.LOW)) {
			onCommand(Bukkit.getConsoleSender(), null, null, new String[0]);
		}
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

		attemptDeletionActivation();

		if (args.length > 0) {
			args[0] = args[0].toLowerCase();
			if (args[0].equals("reload")) {
				reloadConfig();
				onDisable();
				onEnable();
				sender.sendMessage("Regionerator configuration reloaded, all tasks restarted!");
			}
			if (args[0].equals("pause") || args[0].equals("stop") ) {
				paused = true;
				sender.sendMessage("Paused Regionerator. Use /regionerator resume to resume.");
				return true;
			}
			if (args[0].equals("resume") || args[0].equals("unpause") || args[0].equals("start")) {
				paused = false;
				sender.sendMessage("Resumed Regionerator. Use /regionerator pause to pause.");
				return true;
			}
			return false;
		}

		SimpleDateFormat format = new SimpleDateFormat("HH:mm 'on' dd/MM");
		for (String worldName : worlds) {
			long activeAt = getConfig().getLong("delete-this-to-reset-plugin." + worldName);
			if (activeAt > System.currentTimeMillis()) {
				// Not time yet.
				sender.sendMessage(worldName + " - Gathering data, regeneration starts " + format.format(new Date(activeAt)));
				continue;
			}

			if (deletionRunnables.containsKey(worldName)) {
				DeletionRunnable runnable = deletionRunnables.get(worldName);
				sender.sendMessage(runnable.getRunStats());
				if (runnable.getNextRun() < Long.MAX_VALUE) {
					sender.sendMessage("Cycle is finished. Next run scheduled for " + format.format(runnable.getNextRun()));
				}
				continue;
			}

			// Wat.
			getLogger().severe("Deletion cycle failed to start for " + worldName + "! Please report this issue if you see any errors!");
		}
		return true;
	}

	@Override
	public void onDisable() {
		getServer().getScheduler().cancelTasks(this);
		chunkFlagger.save();
	}

	public long getFlagDuration() {
		return flagDuration;
	}

	public int getChunkFlagRadius() {
		return getConfig().getInt("chunk-flag-radius");
	}

	public long getTicksPerFlag() {
		return ticksPerFlag;
	}

	public long getTicksPerFlagAutosave() {
		return ticksPerFlagAutosave;
	}

	public int getRegionsPerCheck() {
		return getConfig().getInt("regions-per-deletion");
	}

	public long getTicksPerDeletionCheck() {
		return getConfig().getLong("ticks-per-deletion");
	}

	public long getMillisecondsBetweenDeletionCycles() {
		return millisBetweenCycles;
	}

	public void attemptDeletionActivation() {
		// TODO support Java 7?
		deletionRunnables.entrySet().removeIf(entry -> entry.getValue().getNextRun() < System.currentTimeMillis());

		if (isPaused()) {
			return;
		}

		for (String worldName : worlds) {
			if (getConfig().getLong("delete-this-to-reset-plugin." + worldName) > System.currentTimeMillis()) {
				// Not time yet.
				continue;
			}
			if (deletionRunnables.containsKey(worldName)) {
				// Already running/ran
				continue;
			}
			World world = Bukkit.getWorld(worldName);
			if (world == null) {
				// World is not loaded.
				continue;
			}
			DeletionRunnable runnable = new DeletionRunnable(this, world);
			runnable.runTaskTimer(this, 0, getTicksPerDeletionCheck());
			deletionRunnables.put(worldName, runnable);
			if (debug(DebugLevel.LOW)) {
				debug("Deletion run scheduled for " + world.getName());
			}
		}
	}

	public List<String> getActiveWorlds() {
		return worlds;
	}

	public List<Hook> getProtectionHooks() {
		return protectionHooks;
	}

	public ChunkFlagger getFlagger() {
		return chunkFlagger;
	}

	public boolean isPaused() {
		return paused;
	}

	public boolean debug(DebugLevel level) {
		return debugLevel.ordinal() >= level.ordinal();
	}

	public void debug(String message) {
		getLogger().info(message);
	}
}
