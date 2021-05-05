/*
 * Copyright (c) 2015-2021 by Jikoo.
 *
 * Regionerator is licensed under a Creative Commons
 * Attribution-ShareAlike 4.0 International License.
 *
 * You should have received a copy of the license along with this
 * work. If not, see <http://creativecommons.org/licenses/by-sa/4.0/>.
 */

package com.github.jikoo.regionerator.commands;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Utility class for common tab completions.
 */
public class TabCompleter {

	/**
	 * Offer tab completions for whole numbers.
	 *
	 * @param argument the argument to complete
	 * @return integer options
	 */
	public static @NotNull List<String> completeInteger(@NotNull String argument) {
		// Ensure existing argument is actually a number
		if (!argument.isEmpty()) {
			try {
				Integer.parseInt(argument);
			} catch (NumberFormatException e) {
				return Collections.emptyList();
			}
		}

		List<String> completions = new ArrayList<>(10);
		for (int i = 0; i < 10; ++i) {
			completions.add(argument + i);
		}

		return completions;
	}

	/**
	 * Offer tab completions for a given Enum.
	 *
	 * @param argument the argument to complete
	 * @param enumClazz the Enum to complete for
	 * @return the matching Enum values
	 */
	public static @NotNull List<String> completeEnum(
			@NotNull String argument,
			@NotNull Class<? extends Enum<?>> enumClazz) {
		argument = argument.toLowerCase(Locale.ENGLISH);
		List<String> completions = new ArrayList<>();

		for (Enum<?> enumConstant : enumClazz.getEnumConstants()) {
			String name = enumConstant.name().toLowerCase();
			if (name.startsWith(argument)) {
				completions.add(name);
			}
		}

		return completions;
	}

	/**
	 * Offer tab completions for a given array of Strings.
	 *
	 * @param argument the argument to complete
	 * @param options the Strings which may be completed
	 * @return the matching Strings
	 */
	public static @NotNull List<String> completeString(
			@NotNull String argument,
			@NotNull String @NotNull [] options) {
		argument = argument.toLowerCase(Locale.ENGLISH);
		List<String> completions = new ArrayList<>();

		for (String option : options) {
			if (option.startsWith(argument)) {
				completions.add(option);
			}
		}

		return completions;
	}

	/**
	 * Offer tab completions for visible online Players' names.
	 *
	 * @param sender the command's sender
	 * @param argument the argument to complete
	 * @return the matching Players' names
	 */
	public static @NotNull List<String> completeOnlinePlayer(@Nullable CommandSender sender, @NotNull String argument) {
		List<String> completions = new ArrayList<>();
		Player senderPlayer = sender instanceof Player ? (Player) sender : null;

		for (Player player : Bukkit.getOnlinePlayers()) {
			if (senderPlayer != null && !senderPlayer.canSee(player)) {
				continue;
			}

			if (StringUtil.startsWithIgnoreCase(player.getName(), argument)) {
				completions.add(player.getName());
			}
		}

		return completions;
	}

	/**
	 * Offer tab completions for a given array of Objects.
	 *
	 * @param argument the argument to complete
	 * @param converter the Function for converting the Object into a comparable String
	 * @param options the Objects which may be completed
	 * @return the matching Strings
	 */
	public static <T> @NotNull List<String> completeObject(
			@NotNull String argument,
			@NotNull Function<T, String> converter,
			@NotNull T @NotNull [] options) {
		argument = argument.toLowerCase(Locale.ENGLISH);
		List<String> completions = new ArrayList<>();

		for (T option : options) {
			String optionString = converter.apply(option).toLowerCase();
			if (optionString.startsWith(argument)) {
				completions.add(optionString);
			}
		}

		return completions;
	}

	private TabCompleter() {}

}
