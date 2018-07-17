package net.johnglassmyer.dsun.common.gff;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import net.johnglassmyer.dsun.common.gff.GffiTable.TableType;

public class GffFile {
	public static GffFile create(byte[] gffFileBytes) {
		Map<String, GffiTable> tablesByTag = createTables(gffFileBytes);

		return new GffFile(gffFileBytes, tablesByTag);
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
			int secondaryTablePosition = gffiTable.getOffset(tagAndIndex.getValue());
			tablesByTag.put(tagAndIndex.getKey(),
					GffiTable.create(bytes, secondaryTablePosition, TableType.SECONDARY));
		}

		return tablesByTag;
	}

	private static String readTag(ByteBuffer buffer) {
		byte[] tagBytes = new byte[4];
		buffer.get(tagBytes);
		return new String(tagBytes, StandardCharsets.US_ASCII);
	}

	private final byte[] bytes;
	private final Map<String, GffiTable> tablesByTag;

	GffFile(byte[] gffFileBytes, Map<String, GffiTable> tablesByTag) {
		this.bytes = gffFileBytes;
		this.tablesByTag = tablesByTag;
	}

	public Map<String, GffiTable> getTablesByTag() {
		return Collections.unmodifiableMap(tablesByTag);
	}

	public byte[] getChunkData(String tag, int index) {
		GffiTable table = tablesByTag.get(tag);
		byte[] chunkData = new byte[table.getSize(index)];
		System.arraycopy(bytes, table.getOffset(index), chunkData, 0, table.getSize(index));
		return chunkData;
	}

	public byte[] replaceChunk(String tag, int index, byte[] replacement) {
		GffiTable table = tablesByTag.get(tag);

		byte[] bytesWithReplacement;

		int newSize = replacement.length;
		int newOffset;

		if (newSize <= table.getSize(index)) {
			// The replacement fits in the old space.

			bytesWithReplacement = bytes.clone();

			newOffset = table.getOffset(index);
		} else {
			// The (larger) replacement must be appended to the end of the file.

			bytesWithReplacement = new byte[bytes.length + newSize];
			System.arraycopy(bytes, 0, bytesWithReplacement, 0, bytes.length);

			newOffset = bytes.length;
		}

		System.arraycopy(replacement, 0, bytesWithReplacement, newOffset, newSize);

		GffiTable replacementTable = GffiTable.create(
				bytesWithReplacement, table.getStartPosition(), table.getType());
		replacementTable.setOffset(index, newOffset);
		replacementTable.setSize(index, newSize);

		return bytesWithReplacement;
	}
}