package net.johnglassmyer.dsun.common.gff;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GffFile {
	private static class SecondaryTableDescriptor {
		final int secondaryTableIndex;
		final int resourceNumberingOffset;

		SecondaryTableDescriptor(int secondaryTableIndex, int resourceNumberingOffset) {
			this.secondaryTableIndex = secondaryTableIndex;
			this.resourceNumberingOffset = resourceNumberingOffset;
		}
	}

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

		Map<String, SecondaryTableDescriptor> secondaryTableDescriptorForTag = new HashMap<>();
		int numberOfTags = buffer.getShort();
		for (int iTag = 0; iTag < numberOfTags; iTag++) {
			String tag = readTag(buffer);
			if (tablesByTag.containsKey(tag)) {
				throw new IllegalStateException("Encountered a second table for tag " + tag);
			}

			int numberOfChunksIfPrimary = buffer.getInt();
			if (numberOfChunksIfPrimary > 0) {
				int tableStartPosition = buffer.position() - 4;
				GffiTable table = PrimaryGffiTable.create(bytes, tableStartPosition);
				tablesByTag.put(tag, table);
				buffer.position(tableStartPosition + table.getTotalSize());
			} else {
				buffer.getInt();

				int secondaryTableIndex = buffer.getInt();
				int resourceNumberingOffset = buffer.position();
				secondaryTableDescriptorForTag.put(tag,
						new SecondaryTableDescriptor(secondaryTableIndex, resourceNumberingOffset));

				int numberSegmentCount = buffer.getInt();
				for (int i = 0; i < numberSegmentCount; i++) {
					buffer.getInt();
					buffer.getInt();
				}
			}
		}

		GffiTable gffiTable = tablesByTag.get("GFFI");
		for (Map.Entry<String, SecondaryTableDescriptor> tagAndDescriptor:
				secondaryTableDescriptorForTag.entrySet()) {
			SecondaryTableDescriptor descriptor = tagAndDescriptor.getValue();
			int secondaryTableOffset = gffiTable.getOffset(descriptor.secondaryTableIndex);
			tablesByTag.put(tagAndDescriptor.getKey(), SecondaryGffiTable.create(
					bytes, secondaryTableOffset, descriptor.resourceNumberingOffset));
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

	public List<ResourceDescriptor> describeResources() {
		List<ResourceDescriptor> descriptors = new ArrayList<>();

		for (Map.Entry<String, GffiTable> tagAndTable : tablesByTag.entrySet()) {
			String tag = tagAndTable.getKey();
			GffiTable table = tagAndTable.getValue();
			for (int i = 0; i < table.getEntryCount(); i++) {
				descriptors.add(new ResourceDescriptor(
						tag, table.getResourceNumber(i), table.getOffset(i), table.getSize(i)));
			}
		}

		descriptors.sort((r1, r2) -> r1.offset - r2.offset);

		return descriptors;
	}

	public byte[] getResourceData(String tag, int resourceNumber) {
		GffiTable table = tablesByTag.get(tag);
		int index = table.getIndexForResourceNumber(resourceNumber).orElseThrow(
				() -> new IllegalArgumentException(
						String.format("no resource %s-%d in GFF file", tag, resourceNumber)));
		byte[] data = new byte[table.getSize(index)];
		System.arraycopy(
				bytes, table.getOffset(index), data, 0, table.getSize(index));
		return data;
	}

	public byte[] replaceResource(String tag, int resourceNumber, byte[] replacement) {
		GffiTable table = tablesByTag.get(tag);

		int index = table.getIndexForResourceNumber(resourceNumber).orElseThrow(
				() -> new IllegalArgumentException(
						String.format("no resource %s-%d in GFF file", tag, resourceNumber)));

		int newSize = replacement.length;
		int newOffset;
		if (newSize <= table.getSize(index)) {
			// The replacement fits in the old space.
			newOffset = table.getOffset(index);
		} else {
			// The (larger) replacement must be appended to the end of the file.
			newOffset = bytes.length;
		}

		table.setOffset(index, newOffset);
		table.setSize(index, newSize);

		byte[] bytesWithReplacement;
		if (newOffset < bytes.length) {
			bytesWithReplacement = bytes.clone();
		} else {
			bytesWithReplacement = new byte[bytes.length + newSize];
			System.arraycopy(bytes, 0, bytesWithReplacement, 0, bytes.length);
		}

		System.arraycopy(replacement, 0, bytesWithReplacement, newOffset, newSize);

		return bytesWithReplacement;
	}
}