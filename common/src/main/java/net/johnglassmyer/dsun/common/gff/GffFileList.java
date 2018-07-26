package net.johnglassmyer.dsun.common.gff;

import java.util.List;
import java.util.stream.Collectors;

import com.google.common.collect.ImmutableList;

public class GffFileList implements GffReader {
	private final List<GffFile> gffFiles;

	public GffFileList(List<GffFile> gffFiles) {
		this.gffFiles = ImmutableList.copyOf(gffFiles);
	}

	@Override
	public List<ResourceDescriptor> describeResources() {
		return gffFiles.stream()
				.map(g -> g.describeResources())
				.flatMap(List::stream)
				.collect(Collectors.toList());
	}

	@Override
	public boolean hasResource(String tag, int resourceNumber) {
		return gffFiles.stream().anyMatch(g -> g.hasResource(tag, resourceNumber));
	}

	@Override
	public byte[] getResourceData(String tag, int resourceNumber) {
		GffFile gffFile = gffFiles.stream()
				.filter(g -> g.hasResource(tag, resourceNumber))
				.findFirst()
				.get();

		return gffFile.getResourceData(tag, resourceNumber);
	}
}
