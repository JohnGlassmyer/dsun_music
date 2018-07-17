package net.johnglassmyer.dsun.common.gff;

import static com.google.common.base.Preconditions.checkArgument;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class GffiTable {
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
	private final GffiTable.TableType tableType;

	static GffiTable create(byte[] bytes, int startPosition, GffiTable.TableType type) {
		ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
		buffer.position(startPosition);
		int numberOfEntries = buffer.getInt();
		return new GffiTable(buffer, startPosition, numberOfEntries, type);
	}

	private GffiTable(ByteBuffer buffer, int startPosition, int numberOfEntries, GffiTable.TableType type) {
		this.buffer = buffer;
		this.startPosition = startPosition;
		this.numberOfEntries = numberOfEntries;
		this.tableType = type;
	}

	int getStartPosition() {
		return startPosition;
	}

	TableType getType() {
		return tableType;
	}

	int getTotalSize() {
		return 4 + numberOfEntries * tableType.entrySize();
	}

	public int getNumberOfChunks() {
		return numberOfEntries;
	}

	public int getOffset(int index) {
		checkArgument(index < numberOfEntries);
		return buffer.getInt(entryPosition(index) + tableType.offsetOffset());
	}

	void setOffset(int index, int offset) {
		checkArgument(index < numberOfEntries);
		buffer.putInt(entryPosition(index) + tableType.offsetOffset(), offset);
	}

	public int getSize(int index) {
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