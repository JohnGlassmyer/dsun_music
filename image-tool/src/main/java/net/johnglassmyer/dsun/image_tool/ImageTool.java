package net.johnglassmyer.dsun.image_tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import joptsimple.OptionException;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import joptsimple.util.PathConverter;
import joptsimple.util.PathProperties;
import mil.nga.tiff.FileDirectory;
import mil.nga.tiff.TIFFImage;
import mil.nga.tiff.TiffWriter;
import net.johnglassmyer.dsun.common.image.Color;
import net.johnglassmyer.dsun.common.image.Palette;
import net.johnglassmyer.dsun.common.image.TiffWriting;
import net.johnglassmyer.dsun.common.options.OptionsProcessor;
import net.johnglassmyer.dsun.common.options.OptionsWithHelp;
import net.johnglassmyer.dsun.common.image.ImageFrame;
import net.johnglassmyer.dsun.common.image.ImageReading;

public class ImageTool {
	private static abstract class Options implements OptionsWithHelp {
		static class Processor extends OptionsProcessor<Options> {
			@Override
			public Options parseArgs(String[] args) throws OptionException {
				OptionParser parser = new OptionParser();
				parser.posixlyCorrect(true);

				OptionSpec<Void> help = parser.accepts("help");
				OptionSpec<Path> image = parser.accepts("image")
						.withRequiredArg()
						.withValuesConvertedBy(new PathConverter(PathProperties.FILE_EXISTING));
				OptionSpec<Void> describe = parser.accepts("describe");
				OptionSpec<Path> outputTiff = parser.accepts("output-tiff")
						.withRequiredArg()
						.withValuesConvertedBy(new PathConverter());
				OptionSpec<Path> pal = parser.accepts("pal")
						.withRequiredArg()
						.withValuesConvertedBy(new PathConverter(PathProperties.FILE_EXISTING));

				OptionSet optionSet = parser.parse(args);

				return new Options(
						optionSet.valueOfOptional(image),
						optionSet.has(describe),
						optionSet.valueOfOptional(outputTiff),
						optionSet.valueOfOptional(pal)) {
					@Override
					public boolean isHelpRequested() {
						return optionSet.has(help);
					}
				};
			}

			@Override
			public boolean isUsageValid(Options options) {
				return options.image.isPresent()
						&& (options.describe
								|| (options.outputTiff.isPresent() && options.pal.isPresent()));
			}

			@Override
			public void printUsage() {
				System.out.println("To describe an image:");
				System.out.println("  java -jar image-tool.jar --image=<imageFile> --describe");
				System.out.println();
				System.out.println("To convert an image to TIFF:");
				System.out.println("  java -jar image-tool.jar "
						+ "--image=<imageFile> --output-tiff=<tiffFile> --pal=<palFile>");
			}
		}

		final Optional<Path> image;
		final boolean describe;
		final Optional<Path> outputTiff;
		final Optional<Path> pal;

		Options(
				Optional<Path> image,
				boolean describe,
				Optional<Path> outputTiff,
				Optional<Path> pal) {
			this.image = image;
			this.describe = describe;
			this.pal = pal;
			this.outputTiff = outputTiff;
		}
	}

	public static void main(String[] args) throws IOException {
		Options options = new Options.Processor().process(args);

		byte[] imageBytes = Files.readAllBytes(options.image.get());
		List<ImageFrame> frames = ImageReading.extractFrames(imageBytes);

		int maxWidth = frames.stream().mapToInt(frame -> frame.width).max().orElse(0);
		int maxHeight = frames.stream().mapToInt(frame -> frame.height).max().orElse(0);

		if (options.describe) {
			if (frames.isEmpty()) {
				System.out.println("image has 0 frames");
			} else {
				System.out.format("image has %d frames (max width: %d, max height: %d)\n",
						frames.size(), maxWidth, maxHeight);

				for (int i = 0; i < frames.size(); i++) {
					ImageFrame frame = frames.get(i);
					System.out.format("  frame %d (width: %d, height: %d)\n",
							i, frame.width, frame.height);
				}
			}
		}

		if (options.pal.isPresent() && options.outputTiff.isPresent()) {
			Palette palette = Palette.fromPalData(Files.readAllBytes(options.pal.get()));

			List<FileDirectory> tiffDirectories = frames.stream()
					.map(frame -> TiffWriting.createRgbTiffDirectory(
							maxWidth, maxHeight, sampleSetter -> {
				byte[] pixels = frame.getPixels();
				BitSet alphaMask = frame.getAlphaMask();
				for (int iX = 0; iX < frame.width; iX++) {
					for (int iY = 0; iY < frame.height; iY++) {
						int pixelOffset = iY * frame.width + iX;
						Color color = palette.getColor(Byte.toUnsignedInt(pixels[pixelOffset]));
						int alpha = alphaMask.get(pixelOffset) ? 255 : 0;
						sampleSetter.set(iX, iY, color.red, color.green, color.blue, alpha);
					}
				}}))
					.collect(Collectors.toList());

			TiffWriter.writeTiff(options.outputTiff.get().toFile(), new TIFFImage(tiffDirectories));
		}
	}
}
