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

import static com.google.common.base.Preconditions.checkArgument;
import static net.johnglassmyer.dsun.common.JoptSimpleUtil.ofOptionValueOrEmpty;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;

import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import net.johnglassmyer.dsun.gff.GffTool.GffiTable.TableType;

public class GffTool {
	static class ChunkLocation {
		final int start;
		final int length;

		ChunkLocation(int start, int length) {
			this.start = start;
			this.length = length;
		}

		@Override
		public String toString() {
			return String.format("(%x, %x)", start, length);
		}
	}

	static class Chunk {
		final ChunkLocation location;
		final byte[] data;

		Chunk(ChunkLocation location, byte[] data) {
			this.location = location;
			this.data = data;
		}
	}

	static class GffiTable {
		enum TableType {
			PRIMARY {
				@Override
				int entrySize() {
					return 12;
				}

				@Override
				int offsetOffset() {
					return 4;
				}

				@Override
				int sizeOffset() {
					return 8;
				}
			},

			SECONDARY {
				@Override
				int entrySize() {
					return 8;
				}

				@Override
				int offsetOffset() {
					return 0;
				}

				@Override
				int sizeOffset() {
					return 4;
				}
			},

			;

			abstract int entrySize();

			abstract int offsetOffset();

			abstract int sizeOffset();
		}

		private final ByteBuffer buffer;
		private final int startPosition;
		private final int numberOfEntries;
		private final TableType tableType;

		static GffiTable create(byte[] bytes, int startPosition, TableType type) {
			ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
			buffer.position(startPosition);
			int numberOfEntries = buffer.getInt();
			return new GffiTable(buffer, startPosition, numberOfEntries, type);
		}

		private GffiTable(ByteBuffer buffer, int startPosition, int numberOfEntries, TableType type) {
			this.buffer = buffer;
			this.startPosition = startPosition;
			this.numberOfEntries = numberOfEntries;
			this.tableType = type;
		}

		int getTotalSize() {
			return 4 + numberOfEntries * tableType.entrySize();
		}

		int getNumberOfEntries() {
			return numberOfEntries;
		}

		int getOffset(int index) {
			checkArgument(index < numberOfEntries);
			return buffer.getInt(entryPosition(index) + tableType.offsetOffset());
		}

		void setOffset(int index, int offset) {
			checkArgument(index < numberOfEntries);
			buffer.putInt(entryPosition(index) + tableType.offsetOffset(), offset);
		}

		int getSize(int index) {
			checkArgument(index < numberOfEntries);
			return buffer.getInt(entryPosition(index) + tableType.sizeOffset());
		}

		void setSize(int index, int size) {
			checkArgument(index < numberOfEntries);
			buffer.putInt(entryPosition(index) + tableType.sizeOffset(), size);
		}

		private int entryPosition(int index) {
			return startPosition + 4 + index * tableType.entrySize();
		}
	}

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

				Map<String, GffiTable> tablesByTag = createTables(bytes);

				if (options.listContents) {
					listContents(tablesByTag, bytes.length);
				}

				if (options.extractToDir.isPresent()) {
					extractAllChunks(bytes, tablesByTag, options.extractToDir.get());
				} else if (options.replaceWith.isPresent()) {
					String tag = options.tag.get();
					int index = options.index.get();
					String replacementFilename = options.replaceWith.get();
					byte[] bytesWithReplacement =
							replaceChunk(bytes, tablesByTag, tag, index, replacementFilename);

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

	private static Map<String, GffiTable> createTables(byte[] bytes) {
		ByteBuffer buffer = ByteBuffer.wrap(bytes);
		buffer.order(ByteOrder.LITTLE_ENDIAN);

		int indexStart = buffer.getInt(12);
		buffer.position(indexStart);

		buffer.getInt();
		buffer.getInt();

		Map<String, GffiTable> tablesByTag = new HashMap<>();

		Map<String, Integer> secondaryTableIndexForTag = new HashMap<String, Integer>();
		int numberOfTags = buffer.getShort();
		for (int iTag = 0; iTag < numberOfTags; iTag++) {
			String tag = readTag(buffer);

			if (tablesByTag.containsKey(tag)) {
				throw new IllegalStateException("Encountered a second table for tag " + tag);
			}

			int numberOfChunksIfPrimary = buffer.getInt();
			if (numberOfChunksIfPrimary > 0) {
				int tableStartPosition = buffer.position() - 4;
				GffiTable table = GffiTable.create(bytes, tableStartPosition, TableType.PRIMARY);
				tablesByTag.put(tag, table);
				buffer.position(tableStartPosition + table.getTotalSize());
			} else {
				buffer.getInt();

				int secondaryTableIndex = buffer.getInt();
				secondaryTableIndexForTag.put(tag, secondaryTableIndex);

				int numberOfUnknowns = buffer.getInt();
				for (int iUnknown = 0; iUnknown < numberOfUnknowns; iUnknown++) {
					buffer.getInt();
					buffer.getInt();
				}
			}
		}

		GffiTable gffiTable = tablesByTag.get("GFFI");
		for (Map.Entry<String, Integer> tagAndIndex : secondaryTableIndexForTag.entrySet()) {
			String tag = tagAndIndex.getKey();
			int secondaryTableIndex = tagAndIndex.getValue();
			int secondaryTablePosition = gffiTable.getOffset(secondaryTableIndex);
			tablesByTag.put(tag,
					GffiTable.create(bytes, secondaryTablePosition, TableType.SECONDARY));
		}

		return tablesByTag;
	}

	private static String readTag(ByteBuffer buffer) {
		byte[] tagBytes = new byte[4];
		buffer.get(tagBytes);
		return new String(tagBytes, StandardCharsets.US_ASCII);
	}

	private static void listContents(Map<String, GffiTable> tablesByTag, int length) {
		NavigableMap<Integer, ChunkDescriptor> descriptorsByOffset = new TreeMap<>();

		for (Map.Entry<String, GffiTable> tagAndTable : tablesByTag.entrySet()) {
			String tag = tagAndTable.getKey();
			GffiTable table = tagAndTable.getValue();
			for (int i = 0; i < table.getNumberOfEntries(); i++) {
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

			int numberOfEntries = tablesByTag.get(descriptor.tag).getNumberOfEntries();
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

	private static void extractAllChunks(
			byte[] bytes, Map<String, GffiTable> tablesByTag, String extractToDir)
			throws IOException {
		Path dirPath = Paths.get(extractToDir);

		OUT.format("  Extracting all chunks to directory %s.\n", dirPath);

		Files.createDirectories(dirPath);

		List<String> sortedTags = new ArrayList<String>(tablesByTag.keySet());
		Collections.sort(sortedTags);

		for (String tag : sortedTags) {
			GffiTable table = tablesByTag.get(tag);

			int numberOfEntries = table.getNumberOfEntries();
			for (int i = 0; i < numberOfEntries; i++) {
				String chunkName = formatChunkName(tag, i, numberOfEntries);
				int offset = table.getOffset(i);
				byte[] chunkData = Arrays.copyOfRange(bytes, offset, offset + table.getSize(i));
				Files.write(dirPath.resolve(chunkName), chunkData);
			}

			OUT.format("    %s: %d chunks\n", tag, numberOfEntries);
		}
	}

	/**
	 * Returns either the original bytes, or a larger copy, with the replacement chunk copied in.
	 */
	private static byte[] replaceChunk(
			byte[] bytes,
			Map<String, GffiTable> tablesByTag,
			String tag,
			int index,
			String replacementFilename) throws IOException {
		byte[] replacement = Files.readAllBytes(Paths.get(replacementFilename));
		int newSize = replacement.length;

		GffiTable table = tablesByTag.get(tag);

		int oldSize = table.getSize(index);
		table.setSize(index, newSize);

		if (newSize <= oldSize) {
			// The replacement fits in the old space.
			int offset = table.getOffset(index);
			System.arraycopy(replacement, 0, bytes, offset, newSize);
			return bytes;
		} else {
			// Append the (larger) replacement to the end of the file.
			table.setOffset(index, bytes.length);
			byte[] enlargedBytes = new byte[bytes.length + newSize];
			System.arraycopy(bytes, 0, enlargedBytes, 0, bytes.length);
			System.arraycopy(replacement, 0, enlargedBytes, bytes.length, newSize);
			return enlargedBytes;
		}
	}
}
