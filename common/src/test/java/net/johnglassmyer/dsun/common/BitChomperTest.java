package net.johnglassmyer.dsun.common;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.ByteOrder.BIG_ENDIAN;
import static java.nio.ByteOrder.LITTLE_ENDIAN;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiFunction;

import org.junit.jupiter.api.Test;

public class BitChomperTest {
	@Test
	void testGetOffsets() {
		byte[] bytes = new byte[] { 0b00110011, 0b01111110 };
		BitChomper chomper = new BitChomper(bytes, 0, 0, BIG_ENDIAN);

		assertThat(chomper.chomp(3)).isEqualTo(0b001);
		assertThat(chomper.byteOffset()).isEqualTo(0);
		assertThat(chomper.bitOffset()).isEqualTo(3);

		assertThat(chomper.chomp(6)).isEqualTo(0b100110);
		assertThat(chomper.byteOffset()).isEqualTo(1);
		assertThat(chomper.bitOffset()).isEqualTo(1);

		assertThat(chomper.chomp(7)).isEqualTo(0b1111110);
		assertThat(chomper.byteOffset()).isEqualTo(2);
		assertThat(chomper.bitOffset()).isEqualTo(0);
	}

	@Test
	void testVaryingChompSize() {
		byte[] bytes = new byte[] {
				0b0_11_0101_1, (byte) 0b0111101_1, (byte) 0b110_1101_1, (byte) 0b011_01110 };

		BitChomper chomper = new BitChomper(bytes, 0, 0, ByteOrder.BIG_ENDIAN);

		assertThat(chomper.chomp(1)).isEqualTo(0b0);
		assertThat(chomper.chomp(2)).isEqualTo(0b11);
		assertThat(chomper.chomp(4)).isEqualTo(0b0101);
		assertThat(chomper.chomp(8)).isEqualTo(0b1011_1101);
		assertThat(chomper.chomp(16)).isEqualTo(0b1110_1101_1011_0111);
	}

	@Test
	void testInvalidBitsPerChomp() {
		byte[] bytes = new byte[10];

		assertThrows(IllegalArgumentException.class, () -> {
			new BitChomper(bytes, 0, 0, LITTLE_ENDIAN).chomp(0);
		});

		assertDoesNotThrow(() -> {
			new BitChomper(bytes, 0, 0, LITTLE_ENDIAN).chomp(16);
		});

		assertThrows(IllegalArgumentException.class, () -> {
			new BitChomper(bytes, 0, 0, LITTLE_ENDIAN).chomp(17);
		});
	}

	@Test
	void testEmptyArray() {
		assertThat(new BitChomper(new byte[] {}, 0, 0, LITTLE_ENDIAN).hasRemaining(1)).isFalse();
	}

	@Test
	void testStartingOffsets() {
		byte[] bytes = new byte[] {0x20, 0x04, 0x60};

		BiFunction<Integer, Integer, BitChomper> chomperWithOffsets = (byteOffset, bitOffset) ->
				new BitChomper(bytes, byteOffset, bitOffset, LITTLE_ENDIAN);

		assertThat(chomperWithOffsets.apply(0, 4).chomp(4)).isEqualTo(0x2);
		assertThat(chomperWithOffsets.apply(1, 0).chomp(4)).isEqualTo(0x4);
		assertThat(chomperWithOffsets.apply(2, 4).chomp(4)).isEqualTo(0x6);
	}

	@Test
	void testInvalidStartingBitOffset() {
		assertThrows(IllegalArgumentException.class, () -> {
			new BitChomper(new byte[2], 0, -1, BIG_ENDIAN);
		});

		assertDoesNotThrow(() -> {
			new BitChomper(new byte[2], 0, 0, BIG_ENDIAN);
		});

		assertDoesNotThrow(() -> {
			new BitChomper(new byte[2], 0, 7, BIG_ENDIAN);
		});

		assertThrows(IllegalArgumentException.class, () -> {
			new BitChomper(new byte[2], 0, 8, BIG_ENDIAN);
		});
	}

	@Test
	void testHasRemaining() {
		BitChomper chomper = new BitChomper(
				new byte[] {0, 0}, 0, 0, LITTLE_ENDIAN);

		int chompSize = 6;

		assertThat(chomper.hasRemaining(chompSize)).isTrue();
		chomper.chomp(chompSize);
		assertThat(chomper.hasRemaining(chompSize)).isTrue();
		chomper.chomp(chompSize);
		assertThat(chomper.hasRemaining(chompSize)).isFalse();
	}

	@Test
	void testBigEndian4Bit() {
		byte [] bytes = new byte[] {0x20, 0x00, 0x04};
		assertChomps(bytes, BIG_ENDIAN, 4, Arrays.asList(0x2, 0x0, 0x0, 0x0, 0x0, 0x4));
	}

	@Test
	void testBigEndian8Bit() {
		byte [] bytes = new byte[] {0x20, 0x00, 0x04};
		assertChomps(bytes, BIG_ENDIAN, 8, Arrays.asList(0x20, 0x00, 0x04));
	}

	@Test
	void testBigEndian12Bit() {
		byte [] bytes = new byte[] {0x20, 0x00, 0x04};
		assertChomps(bytes, BIG_ENDIAN, 12, Arrays.asList(0x200, 0x004));
	}

	@Test
	void testBigEndian16Bit() {
		byte [] bytes = new byte[] {0x20, 0x00, 0x04};
		assertChomps(bytes, BIG_ENDIAN, 16, Arrays.asList(0x2000));
	}

	@Test
	void testLittleEndian4Bit() {
		byte [] bytes = new byte[] {0x20, 0x00, 0x04};
		assertChomps(bytes, LITTLE_ENDIAN, 4, Arrays.asList(0x0, 0x2, 0x0, 0x0, 0x4, 0x0));
	}

	@Test
	void testLittleEndian8Bit() {
		byte [] bytes = new byte[] {0x20, 0x00, 0x04};
		assertChomps(bytes, LITTLE_ENDIAN, 8, Arrays.asList(0x20, 0x00, 0x04));
	}

	@Test
	void testLittleEndian12Bit() {
		byte [] bytes = new byte[] {0x20, 0x00, 0x04};
		assertChomps(bytes, LITTLE_ENDIAN, 12, Arrays.asList(0x020, 0x040));
	}

	@Test
	void testLittleEndian16Bit() {
		byte [] bytes = new byte[] {0x20, 0x00, 0x04};
		assertChomps(bytes, LITTLE_ENDIAN, 16, Arrays.asList(0x0020));
	}

	private void assertChomps(
			byte[] bytes, ByteOrder byteOrder, int bitsPerChomp, Iterable<Integer> expectedChomps) {
		BitChomper chomper = new BitChomper(bytes, 0, 0, byteOrder);
		List<Integer> chomps = new ArrayList<>();
		while (chomper.hasRemaining(bitsPerChomp)) {
			chomps.add(chomper.chomp(bitsPerChomp));
		}

		assertThat(chomps).containsExactlyElementsIn(expectedChomps);

		assertThat(chomper.hasRemaining(bitsPerChomp)).isFalse();
	}
}
