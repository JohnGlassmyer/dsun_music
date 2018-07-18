package net.johnglassmyer.dsun.common.image;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

public class ImageReading {
	private static final int NO_MORE_ROWS_ROW_NUMBER = 0xFF;
	private static final int COLUMN_256_FLAG = 0x1;
	private static final int LAST_RUN_FLAG = 0x80;

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

	private static ImageFrame readFrame(byte[] imageBytes, int frameOffset) {
		ByteBuffer buffer = ByteBuffer.wrap(imageBytes);
		buffer.order(ByteOrder.LITTLE_ENDIAN);

		buffer.position(frameOffset);
		int width = Short.toUnsignedInt(buffer.getShort());
		int height = Short.toUnsignedInt(buffer.getShort());

		NavigableMap<Integer, List<PixelRun>> runsByRow = new TreeMap<>();

		while (runsByRow.size() < height) {
			int rowNumber = Byte.toUnsignedInt(buffer.get());
			if (rowNumber == NO_MORE_ROWS_ROW_NUMBER) {
				break;
			}
			if (rowNumber >= height) {
				throw new RuntimeException(String.format("row number %X >= frame height %X", rowNumber, height));
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
			if ((codeByte % 2) == 0) {
				// an even code byte X precedes (X / 2 + 1) plain bytes
				int plainByteCount = codeByte / 2 + 1;
				for (int iPlain = 0; iPlain < plainByteCount; iPlain++) {
					uncompressedData[iUncompressed++] = compressedData[iCompressed++];
				}
			} else {
				// an odd code byte X indicates a repetition of length ((X + 1) / 2)
				int repetitionLength = (codeByte + 1) / 2;
				byte repeatedByte = compressedData[iCompressed++];
				for (int iRepetition = 0; iRepetition < repetitionLength; iRepetition++) {
					uncompressedData[iUncompressed++] = repeatedByte;
				}
			}
		}

		return uncompressedData;
	}
}