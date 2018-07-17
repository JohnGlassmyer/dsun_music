package net.johnglassmyer.dsun.common.image;

import java.util.Arrays;
import mil.nga.tiff.FieldTagType;
import mil.nga.tiff.FileDirectory;
import mil.nga.tiff.Rasters;
import mil.nga.tiff.util.TiffConstants;

public class TiffWriting {
	public interface PixelSampleSetter {
		void set(int x, int y, int red, int green, int blue, int alpha);
	}

	public interface PixelsSetter {
		void setPixels(PixelSampleSetter pixelSetter);
	}

	public static FileDirectory createRgbTiffDirectory(
			int width, int height, PixelsSetter pixelsSetter) {
		Rasters rasters = new Rasters(
				width, height, 4, 8, TiffConstants.SAMPLE_FORMAT_UNSIGNED_INT);

		pixelsSetter.setPixels((x, y, r, g, b, a) -> {
			rasters.setPixelSample(0, x, y, r);
			rasters.setPixelSample(1, x, y, g);
			rasters.setPixelSample(2, x, y, b);
			rasters.setPixelSample(3, x, y, a);
		});

		FileDirectory directory = new FileDirectory();
		directory.setImageWidth(width);
		directory.setImageHeight(height);
		directory.setBitsPerSample(8);
		directory.setCompression(TiffConstants.COMPRESSION_DEFLATE);
		directory.setPhotometricInterpretation(TiffConstants.PHOTOMETRIC_INTERPRETATION_RGB);
		directory.setSamplesPerPixel(4);
		directory.setUnsignedIntegerListEntryValue(
				FieldTagType.ExtraSamples,
				Arrays.asList(TiffConstants.EXTRA_SAMPLES_ASSOCIATED_ALPHA));
		directory.setRowsPerStrip(
				rasters.calculateRowsPerStrip(TiffConstants.PLANAR_CONFIGURATION_CHUNKY));
		directory.setPlanarConfiguration(TiffConstants.PLANAR_CONFIGURATION_CHUNKY);
		directory.setSampleFormat(TiffConstants.SAMPLE_FORMAT_UNSIGNED_INT);
		directory.setWriteRasters(rasters);
		return directory;
	}
}
