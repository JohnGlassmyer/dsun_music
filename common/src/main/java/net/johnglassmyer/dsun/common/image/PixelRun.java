package net.johnglassmyer.dsun.common.image;

class PixelRun {
	final int startX;
	final byte[] pixels;

	PixelRun(int startX, byte[] pixels) {
		this.startX = startX;
		this.pixels = pixels;
	}
}
