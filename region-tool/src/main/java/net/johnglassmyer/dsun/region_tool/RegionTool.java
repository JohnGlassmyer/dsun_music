package net.johnglassmyer.dsun.region_tool;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
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
import net.johnglassmyer.dsun.common.gff.GffFile.NoSuchResourceInGffException;
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
				OptionSpec<Path> gpldataGff = parser.accepts("gpldata-gff")
						.withRequiredArg()
						.withValuesConvertedBy(new PathConverter(PathProperties.FILE_EXISTING));
				OptionSpec<Path> segobjexGff = parser.accepts("segobjex-gff")
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
						optionSet.valueOfOptional(gpldataGff),
						optionSet.valueOfOptional(segobjexGff),
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
						&& options.gpldataGff.isPresent()
						&& options.segobjexGff.isPresent()
						&& options.outputTiff.isPresent();
			}

			@Override
			public void printUsage() {
				System.out.println("To export TIFF of a region:");
				System.out.println("  java -jar region-tool.jar "
						+ "--rgn-gff=<rgnGffFile> "
						+ "--gpldata-gff=<gpldataGffFile> "
						+ "--segobjex-gff=<segobjexGffFile> "
						+ "[--pal=<palFile>] "
						+ "--output-tiff=<tiffFile>");
				System.out.println();
				System.out.println("If pal is not specified, then the matching palette "
						+ "from gpldata-gff will be used.");
			}
		}

		final Optional<Path> rgnGff;
		final Optional<Path> gpldataGff;
		final Optional<Path> segobjexGff;
		final Optional<Path> pal;
		final Optional<Path> outputTiff;

		Options(
				Optional<Path> rgnGff,
				Optional<Path> gpldataGff,
				Optional<Path> segobjexGff,
				Optional<Path> pal,
				Optional<Path> outputTiff) {
			this.rgnGff = rgnGff;
			this.gpldataGff = gpldataGff;
			this.segobjexGff = segobjexGff;
			this.pal = pal;
			this.outputTiff = outputTiff;
		}
	}

	private static final int MAP_WIDTH = 128;
	private static final int MAP_HEIGHT = 98;
	private static final int TILE_DIMENSION = 16;
	private static final int GMAP_WALL_INDEX_BITMASK = 63;

	public static void main(String[] args) throws IOException {
		Options options = new Options.Processor().process(args);

		GffFile rgnGff = GffFile.create(Files.readAllBytes(options.rgnGff.get()));
		List<ResourceDescriptor> rgnDescriptors = rgnGff.describeResources();

		// TODO: support dsun2 regions
		// TODO: support crimson regions

		// TODO: properly render animated colors

		int regionNumber =
				rgnDescriptors.stream().filter(r -> r.tag.equals("RMAP")).findFirst().get().number;

		byte[] rmap = rgnGff.getResourceData("RMAP", regionNumber);
		byte[] gmap = rgnGff.getResourceData("GMAP", regionNumber);

		GffFile gpldataGff = GffFile.create(Files.readAllBytes(options.gpldataGff.get()));
		GffFile segobjexGff = GffFile.create(Files.readAllBytes(options.segobjexGff.get()));

		Palette palette;
		if (options.pal.isPresent()) {
			palette = Palette.fromPalData(Files.readAllBytes(options.pal.get()));
		} else {
			palette = Palette.fromPalData(gpldataGff.getResourceData("PAL ", regionNumber));
		}

		List<Sprite> sprites = new ArrayList<>();
		sprites.addAll(createRmapSprites(rgnGff, rmap));

		List<Sprite> etabAndWallSprites = new ArrayList<>();
		etabAndWallSprites.addAll(createEtabSprites(rgnGff, regionNumber, segobjexGff));
		etabAndWallSprites.addAll(createWallSprites(regionNumber, gmap, gpldataGff));
		// sort sprites by bottom edge to avoid incorrect overlap. works only sometimes.
		// TODO: correctly composite etabs with gmap walls.
		etabAndWallSprites.sort((s1, s2) -> (s1.y + s1.frame.height) - (s2.y + s2.frame.height));
		sprites.addAll(etabAndWallSprites);

		FileDirectory spritesTiffDirectory = renderSprites(palette, sprites);
		spritesTiffDirectory.setStringEntryValue(FieldTagType.PageName, "RMAP, ETAB, GMAP WALL");

		FileDirectory gmapFlagsTiffDirectory = renderGmapFlags(gmap);
		gmapFlagsTiffDirectory.setStringEntryValue(FieldTagType.PageName, "GMAP flags");

		TiffWriter.writeTiff(options.outputTiff.get().toFile(), new TIFFImage(
				Arrays.asList(spritesTiffDirectory, gmapFlagsTiffDirectory)));
	}

	private static List<Sprite> createRmapSprites(GffFile rgnGff, byte[] rmap) {
		List<Sprite> rmapSprites = new ArrayList<>();

		Map<Integer, ImageFrame> tileFramesByNumber = rgnGff.describeResources().stream()
				.filter(r -> r.tag.equals("TILE"))
				.collect(Collectors.toMap(
						r -> r.number,
						r -> ImageReading.extractFrames(
								rgnGff.getResourceData(r.tag, r.number)).get(0)));

		for (int rmapY = 0; rmapY < MAP_HEIGHT; rmapY++) {
			for (int rmapX = 0; rmapX < MAP_WIDTH; rmapX++) {
				int rmapByte = Byte.toUnsignedInt(rmap[rmapY * MAP_WIDTH + rmapX]);
				ImageFrame frame = tileFramesByNumber.get(rmapByte);
				rmapSprites.add(new Sprite(rmapX * TILE_DIMENSION, rmapY * TILE_DIMENSION, frame));
			}
		}

		return rmapSprites;
	}

	private static List<Sprite> createEtabSprites(
			GffFile rgnGff, int regionNumber, GffFile segobjexGff) {
		List<Sprite> etabSprites = new ArrayList<>();

		// TODO: correctly draw mirrored objects

		byte[] etabBytes = rgnGff.getResourceData("ETAB", regionNumber);
		for (int etabOffset = 0; etabOffset < etabBytes.length; etabOffset += 8) {
			ByteBuffer etabBuffer = ByteBuffer.wrap(etabBytes);
			etabBuffer.order(ByteOrder.LITTLE_ENDIAN);
			int etabX = etabBuffer.getShort(etabOffset + 0);
			int etabY = etabBuffer.getShort(etabOffset + 2);
			int etabOjffNumber = etabBuffer.getShort(etabOffset + 6);
			if (etabOjffNumber < 0) {
				int ojffNumber = -etabOjffNumber;
				byte[] ojffBytes = segobjexGff.getResourceData("OJFF", ojffNumber);
				ByteBuffer ojffBuffer = ByteBuffer.wrap(ojffBytes);
				ojffBuffer.order(ByteOrder.LITTLE_ENDIAN);
				int bmpNumber = Short.toUnsignedInt(ojffBuffer.getShort(0xC));
				byte[] bmpBytes = segobjexGff.getResourceData("BMP ", bmpNumber);
				List<ImageFrame> frames = ImageReading.extractFrames(bmpBytes);
				ImageFrame frame = frames.get(0);
				int x = etabX - ojffBuffer.getShort(2);
				int y = etabY - ojffBuffer.getShort(4);
				etabSprites.add(new Sprite(x, y, frame));
			}
		}

		return etabSprites;
	}

	private static List<Sprite> createWallSprites(
			int regionNumber, byte[] gmap, GffFile gpldataGff) {
		List<Sprite> wallSprites = new ArrayList<>();

		for (int gmapY = 0; gmapY < MAP_HEIGHT; gmapY++) {
			gmapX:
			for (int gmapX = 0; gmapX < MAP_WIDTH; gmapX++) {
				int wallIndex = gmap[gmapY * MAP_WIDTH + gmapX] & GMAP_WALL_INDEX_BITMASK;
				if (wallIndex > 0) {
					int wallNumber = regionNumber * 100 + wallIndex - 1;

					if ((wallIndex - 1) > 28) {
						System.out.format("%d @ %d,%d\n", wallNumber, gmapX, gmapY);
					}

					byte[] wallResourceData;
					try {
						wallResourceData = gpldataGff.getResourceData("WALL", wallNumber);
					} catch (NoSuchResourceInGffException nsre) {
						System.err.println(nsre.getMessage());
						continue gmapX;
					}

					ImageFrame frame = ImageReading.extractFrames(wallResourceData).get(0);

					// account for the varying heights of wall images
					int spriteY = gmapY * TILE_DIMENSION + TILE_DIMENSION - frame.height;

					wallSprites.add(new Sprite(
							gmapX * TILE_DIMENSION, spriteY, frame));
				}
			}
		}

		return wallSprites;
	}

	private static FileDirectory renderSprites(Palette palette, List<Sprite> sprites) {
		return TiffWriting.createRgbTiffDirectory(
				MAP_WIDTH * TILE_DIMENSION, MAP_HEIGHT * TILE_DIMENSION,
				(sampleSetter) -> {
			for (Sprite sprite : sprites) {
				ImageFrame frame = sprite.frame;
				BitSet alphaMask = frame.getAlphaMask();
				for (int yInSprite = 0; yInSprite < frame.height; yInSprite++) {
					for (int xInSprite = 0; xInSprite < frame.width; xInSprite++) {
						int pixelIndex = yInSprite * frame.width + xInSprite;
						if (alphaMask.get(pixelIndex)) {
							Color color = palette.getColor(Byte.toUnsignedInt(
									frame.getPixels()[pixelIndex]));
							int x = sprite.x + xInSprite;
							int y = sprite.y + yInSprite;
							if (0 <= x && x < MAP_WIDTH * TILE_DIMENSION
									&& 0 <= y && y < MAP_HEIGHT * TILE_DIMENSION) {
								sampleSetter.set(x, y, color.red, color.green, color.blue, 255);
							}
						}
					}
				}
			}});
	}

	private static FileDirectory renderGmapFlags(byte[] gmap) {
		int width = MAP_WIDTH * TILE_DIMENSION;
		int height = MAP_HEIGHT * TILE_DIMENSION;

		return TiffWriting.createRgbTiffDirectory(width, height, sampleSetter -> {
			for (int tileX = 0; tileX < MAP_WIDTH; tileX++) {
				for (int tileY = 0; tileY < MAP_HEIGHT; tileY++) {
					int gmapByte = Byte.toUnsignedInt(gmap[tileY * MAP_WIDTH + tileX]);
					int flagBits = gmapByte & ~GMAP_WALL_INDEX_BITMASK;
					Color color = new Color(flagBits, flagBits, flagBits);
					for (int yInTile = 0, yInOutput = tileY * TILE_DIMENSION;
							yInTile < TILE_DIMENSION;
							yInTile++, yInOutput++) {
						for (int xInTile = 0, xInOutput = tileX * TILE_DIMENSION;
								xInTile < TILE_DIMENSION;
								xInTile++, xInOutput++) {
							sampleSetter.set(
									xInOutput, yInOutput, color.red, color.green, color.blue, 255);
						}
					}
				}
			}
		});
	}
}

class Sprite {
	final int x;
	final int y;
	final ImageFrame frame;

	Sprite(int x, int y, ImageFrame frame) {
		this.x = x;
		this.y = y;
		this.frame = frame;
	}
}
