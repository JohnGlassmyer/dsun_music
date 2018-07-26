package net.johnglassmyer.dsun.common;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import java.nio.ByteOrder;

public class BitChomper {
	private final byte[] array;
	private final ByteOrder byteOrder;
	private int byteOffset;
	private int bitOffset;

	public BitChomper(
			byte[] array,
			int startingByteOffset,
			int startingBitOffset,
			ByteOrder byteOrder) {
		this.array = checkNotNull(array);
		this.byteOrder = checkNotNull(byteOrder);

		checkArgument(0 <= startingByteOffset);
		this.byteOffset = startingByteOffset;

		checkArgument(0 <= startingBitOffset && startingBitOffset < 8);
		this.bitOffset = startingBitOffset;
	}

	public boolean hasRemaining(int numberOfBits) {
		return (array.length * 8) - (byteOffset * 8 + bitOffset) >= numberOfBits;
	}

	public int byteOffset() {
		return byteOffset;
	}

	public int bitOffset() {
		return bitOffset;
	}

	public int chomp(int numberOfBits) {
		checkArgument(0 < numberOfBits && numberOfBits <= 16);

		int value = 0;

		int bitsFilled = 0;
		while (bitsFilled < numberOfBits) {
			int bitsNeeded = numberOfBits - bitsFilled;

			int currentByte = Byte.toUnsignedInt(array[byteOffset]);

			int bitsFromCurrentByte = Math.min(bitsNeeded, 8 - bitOffset);

			int maskShift;
			int valueShift;
			if (byteOrder == ByteOrder.LITTLE_ENDIAN) {
				maskShift = bitOffset;
				valueShift = bitsFilled;
			} else {
				maskShift = 8 - bitOffset - bitsFromCurrentByte;
				valueShift = bitsNeeded - bitsFromCurrentByte;
			}

			int mask = ((1 << bitsFromCurrentByte) - 1) << maskShift;
			int valueFromByte = (currentByte & mask) >> maskShift;
			value |= valueFromByte << valueShift;

			bitsFilled += bitsFromCurrentByte;
			bitOffset += bitsFromCurrentByte;

			if (bitOffset == 8) {
				byteOffset++;
				bitOffset = 0;
			}
		}

		return value;
	}
}