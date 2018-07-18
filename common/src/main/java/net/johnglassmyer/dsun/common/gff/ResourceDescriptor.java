package net.johnglassmyer.dsun.common.gff;

public class ResourceDescriptor {
	public final String tag;
	public final int number;
	public final int offset;
	public final int size;

	ResourceDescriptor(String tag, int number, int offset, int size) {
		this.tag = tag;
		this.number = number;
		this.offset = offset;
		this.size = size;
	}
}