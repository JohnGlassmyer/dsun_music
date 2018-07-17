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
package net.johnglassmyer.dsun.gff_tool;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;

import joptsimple.OptionException;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import joptsimple.util.PathConverter;
import joptsimple.util.PathProperties;
import net.johnglassmyer.dsun.common.gff.GffFile;
import net.johnglassmyer.dsun.common.gff.GffiTable;
import net.johnglassmyer.dsun.common.options.OptionsProcessor;
import net.johnglassmyer.dsun.common.options.OptionsWithHelp;

public class GffTool {
	private static abstract class Options implements OptionsWithHelp {
		static class Processor extends OptionsProcessor<Options> {
			@Override
			public Options parseArgs(String[] args) throws OptionException {
				OptionParser parser = new OptionParser();
				parser.posixlyCorrect(true);

				OptionSpec<Void> help = parser.accepts("help").forHelp();
				OptionSpec<Path> gff = parser.accepts("gff")
						.withRequiredArg().withValuesConvertedBy(
								new PathConverter(PathProperties.FILE_EXISTING));
				OptionSpec<Void> listContents = parser.accepts("list-contents");
				OptionSpec<Path> extractToDir = parser.accepts("extract-to-dir")
						.withRequiredArg().withValuesConvertedBy(new PathConverter());
				OptionSpec<Path> replaceWith = parser.accepts("replace-with")
						.withRequiredArg().withValuesConvertedBy(
								new PathConverter(PathProperties.FILE_EXISTING));
				OptionSpec<String> tag = parser.accepts("tag")
						.withRequiredArg().ofType(String.class);
				OptionSpec<Integer> index = parser.accepts("index")
						.withRequiredArg().ofType(Integer.class);

				OptionSet optionSet = parser.parse(args);

				return new Options(
						optionSet.valueOfOptional(gff),
						optionSet.has(listContents),
						optionSet.valueOfOptional(extractToDir),
						optionSet.valueOfOptional(replaceWith),
						optionSet.valueOfOptional(tag),
						optionSet.valueOfOptional(index)) {
					@Override
					public boolean isHelpRequested() {
						return optionSet.has(help);
					}
				};
			}

			@Override
			public boolean isUsageValid(Options options) {
				int commandCount = (options.listContents ? 1 : 0)
						+ (options.extractToDir.isPresent() ? 1 : 0)
						+ (options.replaceWith.isPresent() ? 1 : 0);

				// tag and/or index only with replace-with
				boolean areReplaceWithOptionsCorrect = !(options.replaceWith.isPresent()
						^ (options.tag.isPresent() || options.index.isPresent()));

				return options.gff.isPresent() && commandCount == 1 && areReplaceWithOptionsCorrect;
			}

			@Override
			public void printUsage() {
				System.out.println("To list resources in a GFF:");
				System.out.println("  java -jar gff-tool.jar --gff=<gffFile> --list-contents");
				System.out.println("");
				System.out.println("To extract all resources in a GFF into a directory:");
				System.out.println("  java -jar gff-tool.jar --gff=<gffFile> "
						+ "--extract-to-dir=<dir>");
				System.out.println("");
				System.out.println("To replace a resource in a GFF:");
				System.out.println("  java -jar gff-tool.jar --gff=<gffFile> "
						+ "--replace-with=<file> --tag=TAG --index=n");
			}
		}

		final Optional<Path> gff;
		final boolean listContents;
		final Optional<Path> extractToDir;
		final Optional<Path> replaceWith;
		final Optional<String> tag;
		final Optional<Integer> index;

		Options(Optional<Path> gff,
				boolean listContents,
				Optional<Path> extractToDir,
				Optional<Path> replaceWith,
				Optional<String> tag,
				Optional<Integer> index) {
			this.gff = gff;
			this.listContents = listContents;
			this.extractToDir = extractToDir;
			this.replaceWith = replaceWith;
			this.tag = tag;
			this.index = index;
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
		Options options = new Options.Processor().process(args);

		Path gffPath = options.gff.get();

		byte[] bytes = Files.readAllBytes(gffPath);
		GffFile gffFile = GffFile.create(bytes);

		if (options.listContents) {
			listContents(gffFile, bytes.length);
		}

		if (options.extractToDir.isPresent()) {
			extractAllChunks(gffFile, options.extractToDir.get());
		}

		if (options.replaceWith.isPresent()) {
			byte[] replacement = Files.readAllBytes(options.replaceWith.get());

			byte[] bytesWithReplacement = gffFile.replaceChunk(
					options.tag.get(), options.index.get(), replacement);

			Files.write(gffPath, bytesWithReplacement);
		}
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

	private static void extractAllChunks(GffFile gffFile, Path dirPath) throws IOException {
		OUT.format("  Extracting all chunks to directory %s.\n", dirPath);

		Files.createDirectories(dirPath);

		Map<String, GffiTable> tablesByTag = gffFile.getTablesByTag();

		List<String> sortedTags = new ArrayList<>(tablesByTag.keySet());
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
