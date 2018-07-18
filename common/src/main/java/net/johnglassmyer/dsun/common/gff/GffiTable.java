package net.johnglassmyer.dsun.common.gff;

import static com.google.common.base.Preconditions.checkArgument;

import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

abstract class GffiTable {
	protected final ByteBuffer buffer;
	private final int startPosition;
	private final int entryCount;
	private final ConcurrentMap<Integer, Optional<Integer>> indexForResourceNumber;

	protected GffiTable(ByteBuffer buffer, int startPosition, int entryCount) {
		this.buffer = buffer;
		this.startPosition = startPosition;
		this.entryCount = entryCount;
		this.indexForResourceNumber = new ConcurrentHashMap<>();
	}

	int getStartPosition() {
		return startPosition;
	}

	int getTotalSize() {
		return 4 + entryCount * entrySize();
	}

	int getEntryCount() {
		return entryCount;
	}

	abstract int getResourceNumber(int index);

	Optional<Integer> getIndexForResourceNumber(int resourceNumber) {
		return indexForResourceNumber.computeIfAbsent(resourceNumber, number -> {
			for (int i = 0; i < entryCount; i++) {
				if (getResourceNumber(i) == resourceNumber) {
					return Optional.of(i);
				}
			}

			return Optional.empty();
		});
	}

	int getOffset(int index) {
		checkArgument(index < entryCount);
		return buffer.getInt(entryPosition(index) + offsetOffset());
	}

	void setOffset(int index, int offset) {
		checkArgument(index < entryCount);
		buffer.putInt(entryPosition(index) + offsetOffset(), offset);
	}

	int getSize(int index) {
		checkArgument(index < entryCount);
		return buffer.getInt(entryPosition(index) + sizeOffset());
	}

	void setSize(int index, int size) {
		checkArgument(index < entryCount);
		buffer.putInt(entryPosition(index) + sizeOffset(), size);
	}

	private int entryPosition(int index) {
		return startPosition + 4 + index * entrySize();
	}

	protected abstract int entrySize();

	protected abstract int offsetOffset();

	protected abstract int sizeOffset();
}
