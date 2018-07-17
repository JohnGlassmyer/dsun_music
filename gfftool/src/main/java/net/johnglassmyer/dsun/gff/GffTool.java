/*
 * Copyright 2015 John Glassmyer
 * https://github.com/JohnGlassmyer
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
package net.johnglassmyer.dsun.gff;

import static net.johnglassmyer.dsun.common.JoptSimpleUtil.ofOptionValueOrEmpty;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;

import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import net.johnglassmyer.dsun.common.gff.GffFile;
import net.johnglassmyer.dsun.common.gff.GffiTable;

public class GffTool {
	static class Options {
		final boolean helpRequested;
		final boolean listContents;
		final Optional<String> extractToDir;
		final Optional<String> replaceWith;
		final Optional<String> tag;
		final Optional<Integer> index;
		final List<String> filenames;
		private final OptionParser parser;

		Options(boolean help,
				boolean listContents,
				Optional<String> extractToDir,
				Optional<String> replaceWith,
				Optional<String> tag,
				Optional<Integer> index,
				List<String> filenames,
				OptionParser parser) {
			this.helpRequested = help;
			this.listContents = listContents;
			this.extractToDir = extractToDir;
			this.replaceWith = replaceWith;
			this.tag = tag;
			this.index = index;
			this.filenames = Collections.unmodifiableList(filenames);
			this.parser = parser;
		}

		void printHelpOn(OutputStream sink) throws IOException {
			parser.printHelpOn(sink);
		}
	}

	static class ChunkDescriptor {
		final String tag;
		final int index;
		final int offset;
		final int size;

		ChunkDescriptor(String tag, int index, int offset, int size) {
			this.tag = tag;
			this.index = index;
			this.offset = offset;
			this.size = size;
		}
	}

	private static final PrintStream OUT = System.out;

	public static void main(String[] args) throws IOException, URISyntaxException {
		Options options = parseOptions(args);

		// TODO: Instead, require replaceWith with (tag OR index)
		//       once jopt-simple supports mutually dependent options
		boolean tagOrIndexWithoutReplaceWith = options.replaceWith.isPresent()
				^ (options.tag.isPresent() || options.index.isPresent());

		if (options.helpRequested
				|| options.filenames.isEmpty()
				|| (options.extractToDir.isPresent() || options.replaceWith.isPresent())
						&& options.filenames.size() > 1
				|| tagOrIndexWithoutReplaceWith) {
			options.printHelpOn(OUT);
		} else {
			for (String filename : options.filenames) {
				OUT.println(filename);

				Path path = Paths.get(filename);
				byte[] bytes = Files.readAllBytes(path);
				GffFile gffFile = GffFile.create(bytes);

				if (options.listContents) {
					listContents(gffFile, bytes.length);
				}

				if (options.extractToDir.isPresent()) {
					extractAllChunks(gffFile, options.extractToDir.get());
				}

				if (options.replaceWith.isPresent()) {
					byte[] replacement = Files.readAllBytes(Paths.get(options.replaceWith.get()));

					byte[] bytesWithReplacement = gffFile.replaceChunk(
							options.tag.get(), options.index.get(), replacement);

					Files.write(path, bytesWithReplacement);
				}
			}
		}
	}

	private static Options parseOptions(String[] args) {
		OptionParser parser = new OptionParser();

		OptionSpec<Void> helpOption = parser.accepts("help").forHelp();
		OptionSpec<Void> listContentsOption = parser.accepts("list-contents",
				"list the contents of the GFF file(s)");
		OptionSpec<String> extractToDirOption = parser.accepts("extract-to-dir",
				"extract contents of the GFF file to this directory")
				.withRequiredArg().ofType(String.class).describedAs("directory");
		OptionSpec<String> replaceWithOption = parser.accepts("replace-with",
				"replace a chunk in the GFF file (specified by tag and index) with this file")
				.withRequiredArg().ofType(String.class).describedAs("file");
		OptionSpec<String> tagBuilder = parser.accepts("tag",
				"4-character tag of chunk to replace (with --replace-with)")
				.requiredIf(replaceWithOption)
				.withRequiredArg().ofType(String.class).describedAs("xxxx");
		OptionSpec<Integer> indexBuilder = parser.accepts("index",
				"numeric index of chunk to replace (with --replace-with)")
				.requiredIf(replaceWithOption)
				.withRequiredArg().ofType(Integer.class);

		OptionSpec<String> filenamesOption = parser.nonOptions()
				.ofType(String.class).describedAs("GFF file(s)");

		parser.posixlyCorrect(true);

		OptionSet optionSet = parser.parse(args);

		return new Options(
				optionSet.has(helpOption),
				optionSet.has(listContentsOption),
				ofOptionValueOrEmpty(optionSet, extractToDirOption),
				ofOptionValueOrEmpty(optionSet, replaceWithOption),
				ofOptionValueOrEmpty(optionSet, tagBuilder),
				ofOptionValueOrEmpty(optionSet, indexBuilder),
				optionSet.valuesOf(filenamesOption),
				parser);
	}

	private static void listContents(GffFile gffFile, int length) {
		NavigableMap<Integer, ChunkDescriptor> descriptorsByOffset = new TreeMap<>();

		Map<String, GffiTable> tablesByTag = gffFile.getTablesByTag();

		for (Map.Entry<String, GffiTable> tagAndTable : tablesByTag.entrySet()) {
			String tag = tagAndTable.getKey();
			GffiTable table = tagAndTable.getValue();
			for (int i = 0; i < table.getNumberOfChunks(); i++) {
				int offset = table.getOffset(i);
				int size = table.getSize(i);
				descriptorsByOffset.put(offset, new ChunkDescriptor(tag, i, offset, size));
			}
		}

		OUT.println("  Listing contents.");
		OUT.println("    offset   chunk    size");
		OUT.println("    -------- -------- --------");
		String template = "    0x%06X %-8s %8d\n";
		String noChunk = "........";
		int lastEnd = 0;
		for (Map.Entry<Integer, ChunkDescriptor> offsetAndDescriptor :
				descriptorsByOffset.entrySet()) {
			int offset = offsetAndDescriptor.getKey();

			if (lastEnd < offset) {
				OUT.format(template, lastEnd, noChunk, offset - lastEnd);
			}

			ChunkDescriptor descriptor = offsetAndDescriptor.getValue();

			int numberOfEntries = tablesByTag.get(descriptor.tag).getNumberOfChunks();
			String chunkName = formatChunkName(descriptor.tag, descriptor.index, numberOfEntries);

			OUT.format(template, offset, chunkName, descriptor.size);

			lastEnd = offset + descriptor.size;
		}
		if (lastEnd < length) {
			OUT.format(template, lastEnd, noChunk, length - lastEnd);
		}
	}

	private static String formatChunkName(String tag, int index, int numberOfEntries) {
		return String.format("%s-%s", tag, formatIndex(index, numberOfEntries));
	}

	private static String formatIndex(int index, int numberOfEntries) {
		int numberOfIndexDigits = String.valueOf(numberOfEntries - 1).length();
		String indexTemplate = String.format("%%0%dd", numberOfIndexDigits);
		return String.format(indexTemplate, index);
	}

	private static void extractAllChunks(GffFile gffFile, String extractToDir) throws IOException {
		Path dirPath = Paths.get(extractToDir);

		OUT.format("  Extracting all chunks to directory %s.\n", dirPath);

		Files.createDirectories(dirPath);

		Map<String, GffiTable> tablesByTag = gffFile.getTablesByTag();

		List<String> sortedTags = new ArrayList<String>(tablesByTag.keySet());
		Collections.sort(sortedTags);

		for (String tag : sortedTags) {
			GffiTable table = tablesByTag.get(tag);

			int numberOfChunks = table.getNumberOfChunks();
			for (int i = 0; i < numberOfChunks; i++) {
				String chunkName = formatChunkName(tag, i, numberOfChunks);
				byte[] chunkData = gffFile.getChunkData(tag, i);
				Files.write(dirPath.resolve(chunkName), chunkData);
			}

			OUT.format("    %s: %d chunks\n", tag, numberOfChunks);
		}
	}
}
