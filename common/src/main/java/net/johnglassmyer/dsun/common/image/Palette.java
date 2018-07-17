package net.johnglassmyer.dsun.common.image;

import java.util.ArrayList;
import java.util.List;

public class Palette {
	private static final int BYTES_PER_COLOR = 3;

	public static Palette fromPalData(byte[] palData) {
		int numberOfColors = palData.length / 3;

		List<Color> colors = new ArrayList<>(numberOfColors);
		for (int iColor = 0; iColor < numberOfColors; iColor++) {
			int colorOffset = iColor * BYTES_PER_COLOR;
			int red = 4 * Byte.toUnsignedInt(palData[colorOffset + 0]);
			int green = 4 * Byte.toUnsignedInt(palData[colorOffset + 1]);
			int blue = 4 * Byte.toUnsignedInt(palData[colorOffset + 2]);

			colors.add(new Color(red, green, blue));
		}

		return new Palette(colors);
	}

	private final List<Color> colors;

	private Palette(List<Color> colors) {
		this.colors = colors;
	}

	public Color getColor(int paletteIndex) {
		return colors.get(paletteIndex);
	}
}
