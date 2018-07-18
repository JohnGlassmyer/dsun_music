package net.johnglassmyer.dsun.common.gff;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

class PrimaryGffiTable extends GffiTable {
	private static final int ENTRY_SIZE = 12;
	private static final int OFFSET_OFFSET = 4;
	private static final int SIZE_OFFSET = 8;

	static PrimaryGffiTable create(byte[] bytes, int startPosition) {
		ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);

		buffer.position(startPosition);
		int entryCount = buffer.getInt();

		List<Integer> resourceNumbers = readResourceNumbers(buffer, startPosition, entryCount);

		return new PrimaryGffiTable(buffer, startPosition, entryCount, resourceNumbers);
	}

	private static List<Integer> readResourceNumbers(
			ByteBuffer buffer, int tableStartPosition, int entryCount) {
		List<Integer> resourceNumbers = new ArrayList<>();
		for (int i = 0; i < entryCount; i++) {
			resourceNumbers.add(buffer.getInt(tableStartPosition + 4 + i * ENTRY_SIZE));
		}
		return resourceNumbers;
	}

	private final List<Integer> resourceNumbers;

	private PrimaryGffiTable(
			ByteBuffer buffer, int startPosition, int entryCount, List<Integer> resourceNumbers) {
		super(buffer, startPosition, entryCount);

		this.resourceNumbers = resourceNumbers;
	}

	@Override
	protected int entrySize() {
		return ENTRY_SIZE;
	}

	@Override
	protected int offsetOffset() {
		return OFFSET_OFFSET;
	}

	@Override
	protected int sizeOffset() {
		return SIZE_OFFSET;
	}

	@Override
	public int getResourceNumber(int index) {
		return resourceNumbers.get(index);
	}
}
