package net.johnglassmyer.dsun.region_tool;

import static net.johnglassmyer.uncheckers.IoUncheckers.callUncheckedIoRunnable;
import static net.johnglassmyer.uncheckers.IoUncheckers.callUncheckedIoSupplier;
import static net.johnglassmyer.uncheckers.IoUncheckers.uncheckIoFunction;

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
import java.util.Collections;
import java.util.HashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.google.common.collect.Iterables;

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
import net.johnglassmyer.dsun.common.gff.GffFileList;
import net.johnglassmyer.dsun.common.gff.GffReader;
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
				OptionSpec<Path> outputTiff = parser.accepts("output-tiff")
						.withRequiredArg()
						.withValuesConvertedBy(new PathConverter());
				OptionSpec<Path> otherGff = parser.accepts("other-gff")
						.withRequiredArg()
						.withValuesConvertedBy(new PathConverter(PathProperties.FILE_EXISTING));
				OptionSpec<Path> pal = parser.accepts("pal")
						.withRequiredArg()
						.withValuesConvertedBy(new PathConverter(PathProperties.FILE_EXISTING));

				OptionSet optionSet = parser.parse(args);

				return new Options(
						optionSet.valueOfOptional(rgnGff),
						optionSet.valueOfOptional(outputTiff),
						optionSet.valuesOf(otherGff),
						optionSet.valueOfOptional(pal)) {
					@Override
					public boolean isHelpRequested() {
						return optionSet.has(help);
					}
				};
			}

			@Override
			public boolean isUsageValid(Options options) {
				return options.rgnGff.isPresent() && options.outputTiff.isPresent();
			}

			@Override
			public void printUsage() {
				System.out.println("To export TIFF of a region:");
				System.out.println("  java -jar region-tool.jar"
						+ " --rgn-gff=<rgnGffFile>"
						+ " --other-gff=<gffFile>..."
						+ " [--pal=<palFile>]"
						+ " --output-tiff=<tiffFile>");
				System.out.println();
				System.out.println("The --other-gff option should be used multiple times to specify"
						+ " the necessary segobjex/objex and gpldata GFFs; e.g.:");
				System.out.println("  java -jar region-tool.jar --rgn-gff=RGN02.GFF"
						+ " --other-gff=SEGOBJEX.GFF --other-gff=GPLDATA.GFF"
						+ " --output-tiff=RGN02.tiff");
				System.out.println();
				System.out.println("If --pal is not specified, the matching palette will be sought"
						+ " in rgn-gff and other-gff.");
			}
		}

		final Optional<Path> rgnGff;
		final Optional<Path> outputTiff;
		final List<Path> otherGff;
		final Optional<Path> pal;

		Options(
				Optional<Path> rgnGff,
				Optional<Path> outputTiff,
				List<Path> otherGff,
				Optional<Path> pal) {
			this.rgnGff = rgnGff;
			this.outputTiff = outputTiff;
			this.otherGff = otherGff;
			this.pal = pal;
		}
	}

	private static class Sprite {
		final int x;
		final int y;
		final ImageFrame frame;
		final boolean mirrored;

		Sprite(int x, int y, ImageFrame frame, boolean mirrored) {
			this.x = x;
			this.y = y;
			this.frame = frame;
			this.mirrored = mirrored;
		}
	}

	private static class EtabEntry {
		final int x;
		final int y;
		final int yOffset;
		final boolean mirrored;
		final int ojffNumber;

		EtabEntry(int x, int y, int yOffset, boolean mirrored, int etabOjffNumber) {
			this.x = x;
			this.y = y;
			this.yOffset = yOffset;
			this.mirrored = mirrored;
			this.ojffNumber = etabOjffNumber;
		}
	}

	private static class Ojff {
		final int xOffset;
		final int yOffset;
		final ImageFrame frame;

		Ojff(int xOffset, int yOffset, ImageFrame frame) {
			this.xOffset = xOffset;
			this.yOffset = yOffset;
			this.frame = frame;
		}
	}

	private static final int REGION_TILE_WIDTH = 128;
	private static final int REGION_TILE_HEIGHT = 98;
	private static final int TILE_PIXEL_SIZE = 16;
	private static final int REGION_PIXEL_HEIGHT = REGION_TILE_HEIGHT * TILE_PIXEL_SIZE;
	private static final int REGION_PIXEL_WIDTH = REGION_TILE_WIDTH * TILE_PIXEL_SIZE;
	private static final int GMAP_WALL_INDEX_BITMASK = 31;

	public static void main(String[] args) {
		Options options = new Options.Processor().process(args);

		GffFile rgnGff = GffFile.create(callUncheckedIoSupplier(
				() -> Files.readAllBytes(options.rgnGff.get())));

		// TODO: properly render animated colors

		int regionNumber = rgnGff.describeResources().stream()
				.filter(d -> d.tag.equals("GMAP"))
				.findFirst()
				.map(d -> d.number)
				.orElseThrow(() -> new IllegalStateException("no GMAP resource in region GFF"));

		byte[] gmap = rgnGff.getResourceData("GMAP", regionNumber);

		GffFileList gffs = new GffFileList(Stream.concat(
				Stream.of(rgnGff),
				options.otherGff.stream()
						.map(uncheckIoFunction(path -> GffFile.create(Files.readAllBytes(path)))))
				.collect(Collectors.toList()));

		Palette palette = Palette.fromPalData(options.pal
				.map(uncheckIoFunction(path -> Files.readAllBytes(path)))
				.orElseGet(() -> gffs.getResourceData("PAL ", regionNumber)));

		Iterable<Sprite> sprites = Iterables.concat(
				createRmapSprites(gffs),
				createEtabAndWallSprites(regionNumber, gmap, gffs));
		FileDirectory spritesTiffDirectory = renderSprites(palette, sprites);
		spritesTiffDirectory.setStringEntryValue(FieldTagType.PageName, "(R)MAP, ETAB, GMAP WALL");

		FileDirectory gmapFlagsTiffDirectory = renderGmapFlags(gmap);
		gmapFlagsTiffDirectory.setStringEntryValue(FieldTagType.PageName, "GMAP flags");

		List<FileDirectory> directories = new ArrayList<>();
		directories.add(spritesTiffDirectory);
		directories.add(gmapFlagsTiffDirectory);

		callUncheckedIoRunnable(() -> TiffWriter.writeTiff(
				options.outputTiff.get().toFile(),
				new TIFFImage(Arrays.asList(spritesTiffDirectory, gmapFlagsTiffDirectory))));
	}

	private static List<Sprite> createRmapSprites(GffReader gff) {
		List<ResourceDescriptor> descriptors = gff.describeResources();

		ResourceDescriptor mapDescriptor = descriptors.stream()
				.filter(r -> r.tag.equals("RMAP") || r.tag.equals("MAP "))
				.findFirst().orElseThrow(
						() -> new IllegalStateException("no RMAP or MAP resource in region GFF"));

		byte[] map = gff.getResourceData(mapDescriptor.tag, mapDescriptor.number);

		Map<Integer, ImageFrame> tileFramesByNumber = descriptors.stream()
				.filter(r -> r.tag.equals("TILE"))
				.collect(Collectors.toMap(
						r -> r.number,
						r -> ImageReading.extractFrames(
								gff.getResourceData(r.tag, r.number)).get(0)));

		List<Sprite> rmapSprites = new ArrayList<>();
		for (int mapY = 0; mapY < REGION_TILE_HEIGHT; mapY++) {
			for (int mapX = 0; mapX < REGION_TILE_WIDTH; mapX++) {
				int rmapByte = Byte.toUnsignedInt(map[mapY * REGION_TILE_WIDTH + mapX]);
				ImageFrame frame = tileFramesByNumber.get(rmapByte);
				rmapSprites.add(new Sprite(
						mapX * TILE_PIXEL_SIZE, mapY * TILE_PIXEL_SIZE, frame, false));
			}
		}

		return rmapSprites;
	}

	private static List<Sprite> createEtabAndWallSprites(
			int regionNumber, byte[] gmap, GffFileList gffs) {
		// Informed by procedure at file offset 0x22FD2 in DSUN.EXE of Dark Sun: Shattered Lands.

		List<Sprite> etabAndWallSprites = new ArrayList<>();
		int iEtab = 0;
		List<EtabEntry> etabs = createEtabEntries(gffs, regionNumber);
		Map<Integer, Ojff> ojffCache = new HashMap<>();
		Map<Integer, ImageFrame> wallCache = new HashMap<>();
		for (int tileY = 0; tileY < REGION_TILE_HEIGHT; tileY++) {
			while (iEtab < etabs.size() && etabs.get(iEtab).y / TILE_PIXEL_SIZE <= tileY) {
				EtabEntry etab = etabs.get(iEtab);

				int ojffNumber = etab.ojffNumber < 0 ? -etab.ojffNumber : etab.ojffNumber;
				Ojff ojff = ojffCache.computeIfAbsent(ojffNumber, n -> readOjff(gffs, ojffNumber));

				int x = etab.x - ojff.xOffset;
				int y = etab.y - ojff.yOffset - etab.yOffset;

				etabAndWallSprites.add(new Sprite(x, y, ojff.frame, etab.mirrored));

				iEtab++;
			}

			for (int tileX = 0; tileX < REGION_TILE_WIDTH; tileX++) {
				int wallIndex = gmap[tileY * REGION_TILE_WIDTH + tileX] & GMAP_WALL_INDEX_BITMASK;
				if (wallIndex > 0) {
					int wallNumber = regionNumber * 100 + wallIndex - 1;

					ImageFrame frame = wallCache.computeIfAbsent(wallNumber, n -> {
						byte[] wallResourceData;
						try {
							wallResourceData = gffs.getResourceData("WALL", wallNumber);
						} catch (NoSuchResourceInGffException nsre) {
							System.err.println(nsre.getMessage());
							return new ImageFrame(0, 0, Collections.emptyNavigableMap());
						}

						return ImageReading.extractFrames(wallResourceData).get(0);
					});

					int spriteX = tileX * TILE_PIXEL_SIZE + 8 - frame.width / 2;
					int spriteY = tileY * TILE_PIXEL_SIZE + 16 - frame.height;

					etabAndWallSprites.add(new Sprite(spriteX, spriteY, frame, false));
				}
			}
		}

		return etabAndWallSprites;
	}

	private static List<EtabEntry> createEtabEntries(GffReader gffs, int regionNumber) {
		List<EtabEntry> etabEntries = new ArrayList<>();
		byte[] etabBytes = gffs.getResourceData("ETAB", regionNumber);
		ByteBuffer etabBuffer = ByteBuffer.wrap(etabBytes);
		etabBuffer.order(ByteOrder.LITTLE_ENDIAN);
		for (int etabOffset = 0; etabOffset < etabBytes.length; etabOffset += 8) {
			int x = etabBuffer.getShort(etabOffset + 0);
			int y = etabBuffer.getShort(etabOffset + 2);
			int yOffset = etabBuffer.get(etabOffset + 4);
			int byte5 = Byte.toUnsignedInt(etabBuffer.get(etabOffset + 5));
			boolean mirrored = (byte5 & 0x80) != 0;
			int ojffNumber = etabBuffer.getShort(etabOffset + 6);

			etabEntries.add(new EtabEntry(x, y, yOffset, mirrored, ojffNumber));
		}

		return etabEntries;
	}

	private static Ojff readOjff(GffReader gffFile, int ojffNumber) {
		byte[] ojffBytes = gffFile.getResourceData("OJFF", ojffNumber);
		ByteBuffer ojffBuffer = ByteBuffer.wrap(ojffBytes);
		ojffBuffer.order(ByteOrder.LITTLE_ENDIAN);
		int xOffset = ojffBuffer.getShort(2);
		int yOffset = ojffBuffer.getShort(4);
		int bmpNumber = Short.toUnsignedInt(ojffBuffer.getShort(0xC));
		byte[] bmpBytes = gffFile.getResourceData("BMP ", bmpNumber);
		List<ImageFrame> frames = ImageReading.extractFrames(bmpBytes);
		ImageFrame frame = frames.get(0);

		return new Ojff(xOffset, yOffset, frame);
	}

	private static FileDirectory renderSprites(Palette palette, Iterable<Sprite> sprites) {
		return TiffWriting.createRgbTiffDirectory(
				REGION_PIXEL_WIDTH, REGION_PIXEL_HEIGHT, (sampleSetter) -> {
			for (Sprite sprite : sprites) {
				ImageFrame frame = sprite.frame;
				BitSet alphaMask = frame.getAlphaMask();
				for (int yInSprite = 0; yInSprite < frame.height; yInSprite++) {
					for (int xInSprite = 0; xInSprite < frame.width; xInSprite++) {
						int pixelIndex = yInSprite * frame.width + xInSprite;
						if (alphaMask.get(pixelIndex)) {
							Color color = palette.getColor(Byte.toUnsignedInt(
									frame.getPixels()[pixelIndex]));
							int x = sprite.x
									+ (sprite.mirrored ? frame.width - 1 - xInSprite : xInSprite);
							int y = sprite.y + yInSprite;
							if (0 <= x && x < REGION_PIXEL_WIDTH
									&& 0 <= y && y < REGION_PIXEL_HEIGHT) {
								sampleSetter.set(x, y, color.red, color.green, color.blue, 255);
							}
						}
					}
				}
			}
		});
	}

	private static FileDirectory renderGmapFlags(byte[] gmap) {
		return TiffWriting.createRgbTiffDirectory(
				REGION_PIXEL_WIDTH, REGION_PIXEL_HEIGHT, sampleSetter -> {
			for (int tileX = 0; tileX < REGION_TILE_WIDTH; tileX++) {
				for (int tileY = 0; tileY < REGION_TILE_HEIGHT; tileY++) {
					int gmapByte = Byte.toUnsignedInt(gmap[tileY * REGION_TILE_WIDTH + tileX]);
					int flagBits = gmapByte & ~GMAP_WALL_INDEX_BITMASK;
					Color color = new Color(flagBits, flagBits, flagBits);
					for (int yInTile = 0, yInOutput = tileY * TILE_PIXEL_SIZE;
							yInTile < TILE_PIXEL_SIZE;
							yInTile++, yInOutput++) {
						for (int xInTile = 0, xInOutput = tileX * TILE_PIXEL_SIZE;
								xInTile < TILE_PIXEL_SIZE;
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
