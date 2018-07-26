package net.johnglassmyer.dsun.common.gff;

import java.util.List;

public interface GffReader {
	List<ResourceDescriptor> describeResources();

	boolean hasResource(String tag, int resourceNumber);

	byte[] getResourceData(String tag, int resourceNumber);
}
