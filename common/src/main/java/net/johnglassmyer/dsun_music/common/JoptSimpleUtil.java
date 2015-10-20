/*
 * Copyright 2015 John Glassmyer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package net.johnglassmyer.dsun_music.common;

import java.util.List;
import java.util.Optional;

import joptsimple.OptionSet;
import joptsimple.OptionSpec;

public class JoptSimpleUtil {
	public static <T> Optional<T> ofOptionValueOrEmpty(
			OptionSet optionSet, OptionSpec<T> optionSpec) {
		return optionSet.has(optionSpec)
				? Optional.of(optionSet.valueOf(optionSpec))
				: Optional.empty();
	}

	public static <T> Optional<List<T>> ofOptionValuesOrEmpty(
			OptionSet optionSet, OptionSpec<T> optionSpec) {
		return optionSet.has(optionSpec)
				? Optional.of(optionSet.valuesOf(optionSpec))
				: Optional.empty();
	}
}