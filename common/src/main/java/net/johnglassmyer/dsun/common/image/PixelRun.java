package net.johnglassmyer.dsun.common.image;

class PixelRun {
	final int startX;
	private final byte[] pixels;

	PixelRun(int startX, byte[] pixels) {
		this.startX = startX;
		this.pixels = pixels;
	}

	int length() {
		return pixels.length;
	}

	void writeTo(byte[] dest, int destPos) {
		System.arraycopy(pixels, 0, dest, destPos, pixels.length);
	}
}
