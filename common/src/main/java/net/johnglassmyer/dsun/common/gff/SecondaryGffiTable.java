package net.johnglassmyer.dsun.common.gff;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map.Entry;
import java.util.NavigableMap;
import java.util.TreeMap;

class SecondaryGffiTable extends GffiTable {
	static SecondaryGffiTable create(
			byte[] bytes, int tableOffset, int resourceNumberingOffset) {
		ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);

		buffer.position(tableOffset);
		int entryCount = buffer.getInt();

		NavigableMap<Integer, Integer> resourceNumbersByIndex =
				readResourceNumbering(buffer, resourceNumberingOffset);

		return new SecondaryGffiTable(
				buffer, tableOffset, entryCount, resourceNumbersByIndex);
	}

	private static NavigableMap<Integer, Integer> readResourceNumbering(
			ByteBuffer buffer, int resourceNumberingOffset) {
		NavigableMap<Integer, Integer> resourceNumbersByIndex = new TreeMap<>();

		buffer.position(resourceNumberingOffset);
		int segmentCount = buffer.getInt();

		int segmentStartIndex = 0;
		for (int i = 0; i < segmentCount; i++) {
			int segmentStartResourceCount = buffer.getInt();

			resourceNumbersByIndex.put(segmentStartIndex, segmentStartResourceCount);

			int segmentResourceCount = buffer.getInt();
			segmentStartIndex += segmentResourceCount;
		}

		return resourceNumbersByIndex;
	}

	private final NavigableMap<Integer, Integer> resourceNumbersByIndex;

	private SecondaryGffiTable(
			ByteBuffer buffer,
			int tableOffset,
			int entryCount,
			NavigableMap<Integer, Integer> resourceNumbersByIndex) {
		super(buffer, tableOffset, entryCount);

		this.resourceNumbersByIndex = resourceNumbersByIndex;
	}

	@Override
	public int getResourceNumber(int index) {
		Entry<Integer, Integer> numberingSegment = resourceNumbersByIndex.floorEntry(index);
		int segmentStartIndex = numberingSegment.getKey();
		int segmentStartResourceNumber = numberingSegment.getValue();

		return segmentStartResourceNumber + (index - segmentStartIndex);
	}

	@Override
	protected int entrySize() {
		return 8;
	}

	@Override
	protected int offsetOffset() {
		return 0;
	}

	@Override
	protected int sizeOffset() {
		return 4;
	}
}
