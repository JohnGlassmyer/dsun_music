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
package net.johnglassmyer.dsun.xmi_tool;

import static java.nio.ByteOrder.BIG_ENDIAN;
import static net.johnglassmyer.dsun.common.JoptSimpleUtil.ofOptionValueOrEmpty;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.TreeSet;

import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;

public class XmiTool {
	static class Options {
		final boolean helpRequested;
		final boolean zeroRbrnCount;
		final boolean removeApiControl;
		final Optional<Integer> newLoopIterations;
		final boolean unifyLoops;
		final List<String> filenames;
		private final OptionParser parser;

		Options(
				boolean helpRequested,
				boolean zeroRbrnCount,
				boolean removeApiControl,
				Optional<Integer> newLoopIterations,
				boolean unifyLoops,
				List<String> filenames,
				OptionParser parser) {
			this.helpRequested = helpRequested;
			this.zeroRbrnCount = zeroRbrnCount;
			this.removeApiControl = removeApiControl;
			this.newLoopIterations = newLoopIterations;
			this.unifyLoops = unifyLoops;
			this.filenames = Collections.unmodifiableList(filenames);
			this.parser = parser;
		}

		void printHelpOn(OutputStream sink) throws IOException {
			parser.printHelpOn(sink);
		}
	}

	enum XmidiController {
		INDIRECT_CONTROL(0x73, 0),
		FOR(0x74, 1) {
			@Override
			String description(int value) {
				String times = value == 0 || value == 127 ? "∞" : String.valueOf(value);
				return String.format("For(%s)", times);
			}
		},
		NEXT(0x75, -1) {
			@Override
			String description(int value) {
				return (value < 64 ? "Break" : "Next");
			}
		},
		CALLBACK(0x77, 0),
		SEQUENCE_BRANCH_INDEX(0x78, 0) {
			@Override
			String description(int value) {
				return String.format("Sequence Branch Index %d", value);
			}
		},
		UNKNOWN(-1, 0),
		;

		static XmidiController forNumber(int number) {
			for (XmidiController type : values()) {
				if (type.number == number) {
					return type;
				}
			}

			return UNKNOWN;
		}

		final int number;
		final int indentChange;

		private XmidiController(int number, int indentChange) {
			this.number = number;
			this.indentChange = indentChange;
		}

		String description(int value) {
			return "";
		}
	}

	static class ChunkHeader {
		final String tag;
		final int length;

		ChunkHeader(String tag, int length) {
			this.tag = tag;
			this.length = length;
		}
	}

	static class EvntInfo {
		final int evntDataStart;
		final byte[] evntData;
		final Map<XmidiController, NavigableSet<Integer>> xmidiControllerLocations;

		EvntInfo(
				int evntDataStart,
				byte[] evntData,
				Map<XmidiController, NavigableSet<Integer>> xmidiControllerLocations) {
			this.evntDataStart = evntDataStart;
			this.evntData = evntData;
			this.xmidiControllerLocations = xmidiControllerLocations;
		}
	}

	static final PrintStream OUT;
	static {
		try {
			OUT = new PrintStream(System.out, true, "UTF-8");
		} catch (UnsupportedEncodingException e) {
			throw new RuntimeException(e);
		}
	}

	public static void main(String[] args) throws IOException, URISyntaxException {
		Options options = parseOptions(args);

		if (options.helpRequested || options.filenames.isEmpty()) {
			options.printHelpOn(OUT);
		} else {
			for (String filename : options.filenames) {
				Path path = Paths.get(filename);
				OUT.println(path);
				processXmi(path, options);
				OUT.println();
			}
		}
	}

	private static Options parseOptions(String[] args) {
		OptionParser parser = new OptionParser();
		OptionSpec<Void> helpOption = parser.accepts("help").forHelp();
		OptionSpec<Void> zeroRbrnCountOption = parser.accepts("zero-rbrn-count",
				"set the sequence branch index count in the RBRN chunk to 0");
		OptionSpec<Void> removeApiControlOption = parser.accepts("remove-api-control",
				"obliterate occurrences of XMIDI controllers 0x73 "
				+ "(Indirect Controller) and 0x77 (Callback)");
		OptionSpec<Void> unifyLoopsOption = parser.accepts("unify-loops",
				"replace multiple infinite loops with one loop");
		OptionSpec<Integer> setLoopIterationsOption = parser.accepts("set-loop-iterations",
				"set the number of iterations of all infinite loops")
				.withRequiredArg().ofType(Integer.class);
		OptionSpec<String> filenamesOption =
				parser.nonOptions().describedAs("XMI file(s)").ofType(String.class);

		parser.posixlyCorrect(true);

		OptionSet optionSet = parser.parse(args);

		return new Options(
				optionSet.has(helpOption),
				optionSet.has(zeroRbrnCountOption),
				optionSet.has(removeApiControlOption),
				ofOptionValueOrEmpty(optionSet, setLoopIterationsOption),
				optionSet.has(unifyLoopsOption),
				optionSet.valuesOf(filenamesOption),
				parser);
	}

	private static void processXmi(Path path, Options options) throws IOException {
		ByteBuffer buffer = ByteBuffer.wrap(Files.readAllBytes(path));
		buffer.order(ByteOrder.BIG_ENDIAN);

		// FORM
		skip(buffer, readChunkHeader(buffer).length);

		// CAT
		readChunkHeader(buffer);
		readTag(buffer);

		// FORM
		readChunkHeader(buffer);
		readTag(buffer);

		Optional<EvntInfo> evntInfoOptional = Optional.empty();
		Optional<Integer> rbrnDataStartOptional = Optional.empty();
		while (buffer.hasRemaining()) {
			ChunkHeader chunkHeader = readChunkHeader(buffer);
			if (chunkHeader.tag.equals("EVNT")) {
				int evntDataStart = buffer.position();
				byte[] evntData = new byte[chunkHeader.length];
				buffer.get(evntData);

				Map<XmidiController, NavigableSet<Integer>> xmidiControllerLocations =
						parseEvnt(evntDataStart, evntData);
				evntInfoOptional = Optional.of(
						new EvntInfo(evntDataStart, evntData, xmidiControllerLocations));
			} else if (chunkHeader.tag.equals("RBRN")) {
				rbrnDataStartOptional = Optional.of(buffer.position());
				buffer.order(ByteOrder.LITTLE_ENDIAN);
				OUT.format("  RBRN Sequence Branch count: %d\n", buffer.getShort());
				skip(buffer, chunkHeader.length - 2);
			} else {
				skip(buffer, chunkHeader.length);
			}
		}

		if (evntInfoOptional.isPresent()) {
			EvntInfo evntInfo = evntInfoOptional.get();
			int evntDataStart = evntInfo.evntDataStart;
			byte[] evntData = evntInfo.evntData;
			Map<XmidiController, NavigableSet<Integer>> xmidiControllerLocations =
					evntInfo.xmidiControllerLocations;

			if (options.removeApiControl) {
				removeApiControl(evntData, xmidiControllerLocations);
			}

			Map<Integer, Integer> infiniteLoopLocations =
					identifyInfiniteLoops(evntData, xmidiControllerLocations);

			if (options.unifyLoops) {
				infiniteLoopLocations = unifyLoops(evntData, infiniteLoopLocations);
			}

			if (options.newLoopIterations.isPresent()) {
				Integer newloopIterations = options.newLoopIterations.get();
				setAllLoops(evntData, infiniteLoopLocations, newloopIterations);
			}

			buffer.position(evntDataStart);
			buffer.put(evntData);
		}

		if (options.zeroRbrnCount && rbrnDataStartOptional.isPresent()) {
			OUT.println("  Zeroing RBRN sequence branch point count.");
			buffer.position(rbrnDataStartOptional.get());
			buffer.putShort((short) 0);
		}

		boolean modificationsRequested =
				options.zeroRbrnCount ||
				options.removeApiControl ||
				options.unifyLoops ||
				options.newLoopIterations.isPresent();
		if (modificationsRequested) {
			Files.write(path, buffer.array());
		}
	}

	private static void skip(ByteBuffer buffer, int length) {
		buffer.position(buffer.position() + length);
	}

	private static ChunkHeader readChunkHeader(ByteBuffer buffer) {
		String tag = readTag(buffer);

		buffer.order(BIG_ENDIAN);
		int length = buffer.getInt();

		return new ChunkHeader(tag, length);
	}

	private static String readTag(ByteBuffer buffer) {
		byte[] tagBytes = new byte[4];
		buffer.get(tagBytes);
		String tag = new String(tagBytes, StandardCharsets.US_ASCII);

		return tag;
	}

	private static Map<XmidiController, NavigableSet<Integer>> parseEvnt(
			int baseOffset, byte[] evntData) {
		Map<XmidiController, NavigableSet<Integer>> xmidiControllerLocations = new HashMap<>();
		for (XmidiController xmidiController : XmidiController.values()) {
			xmidiControllerLocations.put(xmidiController, new TreeSet<>());
		}

		int currentIndent = 0;
		int accumulatedDelay = 0;
		int accumulatedNoteOns = 0;
		for (int iData = 0; iData < evntData.length; iData++) {
			int currentByte = evntData[iData] & 0xFF;
			if ((currentByte & 0x80) == 0) {
				accumulatedDelay += currentByte;
			} else {
				int numDataBytes;

				int system = currentByte;
				switch (system & 0xF0) {
					case 0x90:
						accumulatedNoteOns++;
						int durationLength = 1;
						while ((evntData[iData + 2 + durationLength] & 0xFF) > 0x80) {
							durationLength++;
						}
						numDataBytes = 2 + durationLength;
						break;

					case 0xB0:
						int controller = evntData[iData + 1] & 0xFF;
						int value = evntData[iData + 2] & 0xFF;
						numDataBytes = 2;

						XmidiController xmidiController = XmidiController.forNumber(controller);
						if (xmidiController != XmidiController.UNKNOWN) {
							xmidiControllerLocations.get(xmidiController).add(iData);

							String description = xmidiController.description(value);
							if (!description.isEmpty()) {
								String delaySymbol = accumulatedNoteOns > 0 ? "♪" : ".";

								int delayToDisplay = accumulatedDelay / 20;
								while (delayToDisplay > 0) {
									int toDisplay = Math.min(delayToDisplay, 60);

									StringBuilder delayDisplay = new StringBuilder();
									for (int iDelay = 0; iDelay < toDisplay; iDelay++) {
										delayDisplay.append(delaySymbol);
									}
									printAtLevel(-1, currentIndent, delayDisplay.toString());

									delayToDisplay -= toDisplay;
								}

								int fileOffset = baseOffset + iData;
								String text = String.format(
										"[%X:%X] %s", system, controller, description);
								printAtLevel(fileOffset, currentIndent, text);

								accumulatedDelay = 0;
								accumulatedNoteOns = 0;
							}

							currentIndent += xmidiController.indentChange;
						}

						break;

					case 0xC0:
						// Program change
						numDataBytes = 1;
						break;

					case 0xD0:
						// Channel pressure
						numDataBytes = 1;
						break;

					case 0xF0:
						if (system == 0xFF) {
							// MIDI-file meta
							int extendedLength = evntData[iData + 2] & 0xFF;
							numDataBytes = 2 + extendedLength;
						} else {
							throw new IllegalStateException(
									String.format("Unhandled system message: 0x%X", system));
						}
						break;

					default:
						numDataBytes = 2;
				}

				iData += numDataBytes;
			}
		}

		return Collections.unmodifiableMap(xmidiControllerLocations);
	}

	private static void printAtLevel(int offset, int level, String text) {
		StringBuilder sb = new StringBuilder();

		if (offset >= 0) {
			sb.append(String.format("  %06X ", offset));
		} else {
			sb.append("  ...... ");
		}

		for(int i = 0; i < level; i++) {
			sb.append("  ");
		}

		sb.append(text);

		OUT.println(sb.toString());
	}

	static void removeApiControl(
			byte[] evntData,
			Map<XmidiController, NavigableSet<Integer>> xmidiControllerLocations) {
		OUT.println("  Removing API control.");
		for (int location : xmidiControllerLocations.get(XmidiController.CALLBACK)) {
			OUT.format("    Obliterating CALLBACK at 0x%04X.\n", location);
			obliterateMessage(evntData, location);
		}
		for (int location : xmidiControllerLocations.get(XmidiController.INDIRECT_CONTROL)) {
			OUT.format("    Obliterating INDIRECT_CONTROL at 0x%04X.\n", location);
			obliterateMessage(evntData, location);
		}
	}

	static Map<Integer, Integer> identifyInfiniteLoops(
			byte[] evntData,
			Map<XmidiController, NavigableSet<Integer>> xmidiControllerLocations) {
		Map<Integer, Integer> infiniteLoops = new HashMap<>();

		NavigableSet<Integer> forLocations =
				new TreeSet<Integer>(xmidiControllerLocations.get(XmidiController.FOR));
		for (int nextLocation : xmidiControllerLocations.get(XmidiController.NEXT)) {
			int matchingForLocation = forLocations.lower(nextLocation);
			int loopLength = evntData[matchingForLocation + 2];
			if (loopLength == 0 || loopLength == 127) {
				infiniteLoops.put(matchingForLocation, nextLocation);
			} else {
				forLocations.remove(matchingForLocation);
			}
		}

		return Collections.unmodifiableMap(infiniteLoops);
	}

	static void setAllLoops(
			byte[] evntData, Map<Integer, Integer> forAndNextLocations, int newLoopLength) {
		for (Map.Entry<Integer, Integer> forAndNextLocation : forAndNextLocations.entrySet()) {
			int forLocation = forAndNextLocation.getKey();
			OUT.format(
					"  Setting loop at 0x%04X to %d iterations.\n", forLocation, newLoopLength);
			evntData[forLocation + 2] = (byte) newLoopLength;
		}
	}

	static Map<Integer, Integer> unifyLoops(
			byte[] evntData, Map<Integer, Integer> forAndNextLocations) {
		if (forAndNextLocations.isEmpty()) {
			return Collections.emptyMap();
		}

		Deque<Entry<Integer, Integer>> loops = new ArrayDeque<>(forAndNextLocations.entrySet());

		OUT.println("  Unifying loops.");

		for (Map.Entry<Integer, Integer> loop : loops) {
			if (loop != loops.getFirst()) {
				int forLocation = loop.getKey();
				OUT.format("    Obliterating FOR at 0x%04X.\n", forLocation);
				obliterateMessage(evntData, forLocation);
			}

			if (loop != loops.getLast()) {
				int nextLocation = loop.getValue();
				OUT.format("    Obliterating NEXT at 0x%04X.\n", nextLocation);
				obliterateMessage(evntData, nextLocation);
			}
		}

		return Collections.singletonMap(loops.getFirst().getKey(), loops.getLast().getKey());
	}

	/** Turn a 3-byte message into a no-op controller message. */
	private static void obliterateMessage(byte[] data, int offset) {
		data[offset] = (byte) 0xBF;
		data[offset+1] = (byte) 0;
		data[offset+2] = (byte) 0;
	}
}
