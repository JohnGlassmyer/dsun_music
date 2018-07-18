package net.johnglassmyer.dsun.region_tool;

import static com.google.common.base.Preconditions.checkState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Arrays;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import joptsimple.OptionException;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import joptsimple.util.PathConverter;
import joptsimple.util.PathProperties;
import mil.nga.tiff.FieldTagType;
import mil.nga.tiff.FileDirectory;
import mil.nga.tiff.TIFFImage;
import mil.nga.tiff.TiffWriter;
import net.johnglassmyer.dsun.common.gff.GffFile;
import net.johnglassmyer.dsun.common.gff.ResourceDescriptor;
import net.johnglassmyer.dsun.common.image.Color;
import net.johnglassmyer.dsun.common.image.Palette;
import net.johnglassmyer.dsun.common.image.ImageFrame;
import net.johnglassmyer.dsun.common.image.ImageReading;
import net.johnglassmyer.dsun.common.image.TiffWriting;
import net.johnglassmyer.dsun.common.options.OptionsProcessor;
import net.johnglassmyer.dsun.common.options.OptionsWithHelp;

public class RegionTool {
	private static abstract class Options implements OptionsWithHelp {
		static class Processor extends OptionsProcessor<Options> {
			@Override
			public Options parseArgs(String[] args) throws OptionException {
				OptionParser parser = new OptionParser();
				parser.posixlyCorrect(true);

				OptionSpec<Void> help = parser.accepts("help");
				OptionSpec<Path> rgnGff = parser.accepts("rgn-gff")
						.withRequiredArg()
						.withValuesConvertedBy(new PathConverter(PathProperties.FILE_EXISTING));
				OptionSpec<Path> pal = parser.accepts("pal")
						.withRequiredArg()
						.withValuesConvertedBy(new PathConverter(PathProperties.FILE_EXISTING));
				OptionSpec<Path> outputTiff = parser.accepts("output-tiff")
						.withRequiredArg()
						.withValuesConvertedBy(new PathConverter());

				OptionSet optionSet = parser.parse(args);

				return new Options(
						optionSet.valueOfOptional(rgnGff),
						optionSet.valueOfOptional(pal),
						optionSet.valueOfOptional(outputTiff)) {
					@Override
					public boolean isHelpRequested() {
						return optionSet.has(help);
					}
				};
			}

			@Override
			public boolean isUsageValid(Options options) {
				return options.rgnGff.isPresent()
						&& options.pal.isPresent()
						&& options.outputTiff.isPresent();
			}

			@Override
			public void printUsage() {
				System.out.println("To export TIFF of a region:");
				System.out.println("  java -jar region-tool.jar "
						+ "--rgn-gff=<rgnGffFile> --pal=<palFile> --output-tiff=<tiffFile>");
			}
		}

		final Optional<Path> rgnGff;
		final Optional<Path> pal;
		final Optional<Path> outputTiff;

		Options(
				Optional<Path> rgnGff,
				Optional<Path> pal,
				Optional<Path> outputTiff) {
			this.rgnGff = rgnGff;
			this.pal = pal;
			this.outputTiff = outputTiff;
		}
	}

	private static final int MAP_WIDTH = 128;
	private static final int MAP_HEIGHT = 98;
	private static final int TILE_DIMENSION = 16;

	public static void main(String[] args) throws IOException {
		Options options = new Options.Processor().process(args);

		GffFile rgnGff = GffFile.create(Files.readAllBytes(options.rgnGff.get()));
		List<ResourceDescriptor> rgnResources = rgnGff.describeResources();

		Map<Integer, ImageFrame> tileFramesByNumber = rgnResources.stream()
				.filter(r -> r.tag.equals("TILE"))
				.collect(Collectors.toMap(
						r -> r.number,
						r -> ImageReading.extractFrames(
								rgnGff.getResourceData(r.tag, r.number)).get(0)));

		// TODO: support dsun2 regions
		// TODO: support crimson regions

		// TODO: display ETAB entries

		// TODO: properly render animated colors

		int regionNumber =
				rgnResources.stream().filter(r -> r.tag.equals("RMAP")).findFirst().get().number;

		byte[] rmap = rgnGff.getResourceData("RMAP", regionNumber);
		byte[] gmap = rgnGff.getResourceData("GMAP", regionNumber);
		checkState(rmap.length == MAP_WIDTH * MAP_HEIGHT, "Unexpected RMAP size");

		Palette palette = Palette.fromPalData(Files.readAllBytes(options.pal.get()));

		FileDirectory rmapTiffDirectory = exportMapToTiffDirectory((tileX, tileY) -> {
			int rmapByte = Byte.toUnsignedInt(rmap[tileY * MAP_WIDTH + tileX]);
			byte[] tilePixels = tileFramesByNumber.get(rmapByte).getPixels();
			Color[] tileColors = new Color[tilePixels.length];
			for (int i = 0; i < tileColors.length; i++) {
				tileColors[i] = palette.getColor(Byte.toUnsignedInt(tilePixels[i]));
			}
			return tileColors;
		});
		rmapTiffDirectory.setStringEntryValue(FieldTagType.PageName, "RMAP");

		FileDirectory gmapTiffDirectory = exportMapToTiffDirectory((tileX, tileY) -> {
			int gmapByte = Byte.toUnsignedInt(gmap[tileY * MAP_WIDTH + tileX]);
			Color[] tileColors = new Color[TILE_DIMENSION * TILE_DIMENSION];
			Arrays.fill(tileColors, new Color(gmapByte, gmapByte, gmapByte));
			return tileColors;
		});
		gmapTiffDirectory.setStringEntryValue(FieldTagType.PageName, "GMAP");

		TiffWriter.writeTiff(options.outputTiff.get().toFile(), new TIFFImage(
				Arrays.asList(rmapTiffDirectory, gmapTiffDirectory)));
	}

	private static FileDirectory exportMapToTiffDirectory(
			BiFunction<Integer, Integer, Color[]> tileCoordsToPixels) {
		int width = MAP_WIDTH * TILE_DIMENSION;
		int height = MAP_HEIGHT * TILE_DIMENSION;

		return TiffWriting.createRgbTiffDirectory(width, height, sampleSetter -> {
			for (int tileX = 0; tileX < MAP_WIDTH; tileX++) {
				for (int tileY = 0; tileY < MAP_HEIGHT; tileY++) {
					Color[] tilePixels = tileCoordsToPixels.apply(tileX, tileY);

					for (int yInTile = 0, yInOutput = tileY * TILE_DIMENSION;
							yInTile < TILE_DIMENSION;
							yInTile++, yInOutput++) {
						for (int xInTile = 0, xInOutput = tileX * TILE_DIMENSION;
								xInTile < TILE_DIMENSION;
								xInTile++, xInOutput++) {
							Color color = tilePixels[yInTile * TILE_DIMENSION + xInTile];
							sampleSetter.set(
									xInOutput, yInOutput, color.red, color.green, color.blue, 255);
						}
					}
				}
			}
		});
	}
}
