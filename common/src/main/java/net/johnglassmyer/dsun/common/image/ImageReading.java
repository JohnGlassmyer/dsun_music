package net.johnglassmyer.dsun.common.image;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeMap;
import java.util.function.BiFunction;
import java.util.function.IntSupplier;
import com.google.common.collect.ImmutableList;

import net.johnglassmyer.dsun.common.BitChomper;

public class ImageReading {
	private static class PixelRunsBuilder {
		private final List<PixelRun> pixelRuns = new ArrayList<>();
		private final ByteArrayOutputStream accumulatedBytes = new ByteArrayOutputStream();
		private OptionalInt optionalStartX = OptionalInt.empty();

		void record(int x, byte pixel) {
			if (!optionalStartX.isPresent()
					|| x != optionalStartX.getAsInt() + accumulatedBytes.size()) {
				// start a new run
				finishRun();
				optionalStartX = OptionalInt.of(x);
			}

			accumulatedBytes.write(pixel);
		}

		void finishRun() {
			if (!optionalStartX.isPresent()) {
				return;
			}

			pixelRuns.add(new PixelRun(optionalStartX.getAsInt(), accumulatedBytes.toByteArray()));

			accumulatedBytes.reset();
			optionalStartX = OptionalInt.empty();
		}

		List<PixelRun> getRuns() {
			return ImmutableList.copyOf(pixelRuns);
		}
	}

	private static class PlanarDecoder {
		private final IntSupplier symbolSource;
		private final List<Byte> dictionary;

		PlanarDecoder(IntSupplier symbolSource, List<Byte> dictionary) {
			this.symbolSource = symbolSource;
			this.dictionary = ImmutableList.copyOf(dictionary);
		}

		Optional<Byte> nextPixel() {
			int symbol = symbolSource.getAsInt();

			byte dictionaryValue = dictionary.get(symbol);

			if (dictionaryValue == 0) {
				return Optional.empty();
			}

			return Optional.of(dictionaryValue);
		}
	}

	private static class PlanSymbolSource implements IntSupplier {
		private final BitChomper chomper;
		private final int bitsPerSymbol;

		PlanSymbolSource(BitChomper chomper, int bitsPerSymbol) {
			this.chomper = chomper;
			this.bitsPerSymbol = bitsPerSymbol;
		}

		@Override
		public int getAsInt() {
			return chomper.chomp(bitsPerSymbol);
		}
	}

	private static class PlnrSymbolSource implements IntSupplier {
		private final BitChomper chomper;
		private final int bitsPerSymbol;
		private int lastValue = 0;
		private int remaining = 0;

		PlnrSymbolSource(BitChomper chomper, int bitsPerSymbol) {
			this.chomper = chomper;
			this.bitsPerSymbol = bitsPerSymbol;
		}

		@Override
		public int getAsInt() {
			if (remaining == 0) {
				int firstCode = chomper.chomp(bitsPerSymbol);
				if (firstCode == 0) {
					int secondCode = chomper.chomp(bitsPerSymbol);
					if (secondCode == 0) {
						lastValue = 0;
						remaining = 1;
					} else {
						// keep last value
						remaining = secondCode + 2;
					}
				} else {
					lastValue = firstCode;
					remaining = 1;
				}
			}

			remaining--;

			return lastValue;
		}
	}

	private static final int NO_MORE_ROWS_ROW_NUMBER = 0xFF;
	private static final int COLUMN_256_FLAG = 0x1;
	private static final int LAST_RUN_FLAG = 0x80;
	private static final String PLAN_TAG = "PLAN";
	private static final String PLNR_TAG = "PLNR";

	public static List<ImageFrame> extractFrames(byte[] imageBytes) {
		ByteBuffer buffer = ByteBuffer.wrap(imageBytes);
		buffer.order(ByteOrder.LITTLE_ENDIAN);

		// skip over file size
		buffer.getInt();

		int numberOfFrames = buffer.getShort();
		List<ImageFrame> frames = new ArrayList<>(numberOfFrames);
		for (int iFrame = 0; iFrame < numberOfFrames; iFrame++) {
			int frameOffset = buffer.getInt();
			frames.add(readFrame(imageBytes, frameOffset));
		}

		return frames;
	}

	private static ImageFrame readFrame(byte[] imageBytes, int frameStartOffset) {
		if (Byte.toUnsignedInt(imageBytes[frameStartOffset + 4]) == 0xFF
				&& imageBytes.length >= frameStartOffset + 5 + PLAN_TAG.length()) {
			byte[] tagBytes = new byte[PLAN_TAG.length()];
			ByteBuffer buffer = ByteBuffer.wrap(imageBytes);
			buffer.position(frameStartOffset + 5);
			buffer.get(tagBytes);

			String tag = new String(tagBytes, StandardCharsets.US_ASCII);
			if (tag.equals(PLAN_TAG)) {
				return readPlanarImageFrame(imageBytes, frameStartOffset, PlanSymbolSource::new);
			} else if (tag.equals(PLNR_TAG)) {
				return readPlanarImageFrame(imageBytes, frameStartOffset, PlnrSymbolSource::new);
			}
		}

		return readRowBasedFrame(imageBytes, frameStartOffset);
	}

	private static ImageFrame readRowBasedFrame(byte[] imageBytes, int frameStartOffset) {
		// The only form of image compression used in Dark Sun: Shattered Lands.

		ByteBuffer buffer = ByteBuffer.wrap(imageBytes);
		buffer.order(ByteOrder.LITTLE_ENDIAN);

		buffer.position(frameStartOffset);
		int width = Short.toUnsignedInt(buffer.getShort());
		int height = Short.toUnsignedInt(buffer.getShort());

		NavigableMap<Integer, List<PixelRun>> runsByRow = new TreeMap<>();
		while (runsByRow.size() < height) {
			int rowNumber = Byte.toUnsignedInt(buffer.get());
			if (rowNumber == NO_MORE_ROWS_ROW_NUMBER) {
				break;
			}
			if (rowNumber >= height) {
				throw new RuntimeException(
						String.format("row number %X >= frame height %X", rowNumber, height));
			}

			List<PixelRun> runs = new ArrayList<>();
			while (true) {
				int startX = Byte.toUnsignedInt(buffer.get());
				int flags = Byte.toUnsignedInt(buffer.get());
				int uncompressedDataLength = Byte.toUnsignedInt(buffer.get());
				int compressedLength = Byte.toUnsignedInt(buffer.get());

				if ((flags & COLUMN_256_FLAG) != 0) {
					startX += 256;
				}

				byte[] compressedData = new byte[compressedLength];
				buffer.get(compressedData);

				byte[] uncompressedData = uncompressPixels(compressedData, uncompressedDataLength);

				runs.add(new PixelRun(startX, uncompressedData));

				if ((flags & LAST_RUN_FLAG) != 0) {
					break;
				}
			}

			runsByRow.put(rowNumber, runs);
		}

		return new ImageFrame(width, height, runsByRow);
	}

	private static byte[] uncompressPixels(byte[] compressedData, int uncompressedLength) {
		byte[] uncompressedData = new byte[uncompressedLength];

		int iUncompressed = 0;
		for (int iCompressed = 0; iCompressed < compressedData.length; ) {
			int codeByte = Byte.toUnsignedInt(compressedData[iCompressed++]);
			int runLength = codeByte / 2 + 1;
			if ((codeByte % 2) == 0) {
				// an even code byte designates a run of plain bytes
				for (int iPlain = 0; iPlain < runLength; iPlain++) {
					uncompressedData[iUncompressed++] = compressedData[iCompressed++];
				}
			} else {
				// an odd code byte designates a duplicated byte
				byte repeatedByte = compressedData[iCompressed++];
				for (int iRepetition = 0; iRepetition < runLength; iRepetition++) {
					uncompressedData[iUncompressed++] = repeatedByte;
				}
			}
		}

		return uncompressedData;
	}

	private static ImageFrame readPlanarImageFrame(
			byte[] bytes,
			int frameStartPosition,
			BiFunction<BitChomper, Integer, IntSupplier> symbolSourceProvider) {
		// Algorithms for PLAN and PLNR reverse-engineered from procedures
		// at file offsets 0x1A1B0 (PLAN) and 0x19287 (PLNR) in DSUN.EXE of Dark Sun 2.

		ByteBuffer buffer = ByteBuffer.wrap(bytes);
		buffer.order(ByteOrder.LITTLE_ENDIAN);

		int width = Short.toUnsignedInt(buffer.getShort(frameStartPosition));
		int height = Short.toUnsignedInt(buffer.getShort(frameStartPosition + 2));

		int bitsPerSymbol = buffer.get(frameStartPosition + 9);
		if (bitsPerSymbol == 0) {
			// empty image frame
			return new ImageFrame(width, height, Collections.emptyNavigableMap());
		}

		int dictionarySize = 1 << bitsPerSymbol;

		List<Byte> pixelValueDictionary = new ArrayList<>(dictionarySize);
		buffer.position(frameStartPosition + 10);
		for (int i = 0; i < dictionarySize; i++) {
			pixelValueDictionary.add(buffer.get());
		}

		int codeStart = frameStartPosition + 10 + dictionarySize;

		IntSupplier symbolSource = symbolSourceProvider.apply(
				new BitChomper(bytes, codeStart, 0, ByteOrder.BIG_ENDIAN), bitsPerSymbol);
		PlanarDecoder decoder = new PlanarDecoder(symbolSource, pixelValueDictionary);

		NavigableMap<Integer, List<PixelRun>> runsByRow = new TreeMap<>();
		for (int y = 0; y < height; y++) {
			PixelRunsBuilder runsBuilder = new PixelRunsBuilder();
			for (int x = 0; x < width; x++) {
				Optional<Byte> optionalPixel = decoder.nextPixel();

				if (optionalPixel.isPresent()) {
					runsBuilder.record(x, optionalPixel.get());
				}
			}

			runsBuilder.finishRun();
			runsByRow.put(y, runsBuilder.getRuns());
		}

		return new ImageFrame(width, height, runsByRow);
	}
}
