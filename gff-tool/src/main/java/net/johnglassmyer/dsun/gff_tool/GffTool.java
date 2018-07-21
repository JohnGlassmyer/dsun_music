package net.johnglassmyer.dsun.gff_tool;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

import joptsimple.OptionException;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import joptsimple.util.PathConverter;
import joptsimple.util.PathProperties;
import net.johnglassmyer.dsun.common.gff.GffFile;
import net.johnglassmyer.dsun.common.gff.ResourceDescriptor;
import net.johnglassmyer.dsun.common.options.OptionsProcessor;
import net.johnglassmyer.dsun.common.options.OptionsWithHelp;

public class GffTool {
	private static abstract class Options implements OptionsWithHelp {
		static class Processor extends OptionsProcessor<Options> {
			@Override
			public Options parseArgs(String[] args) throws OptionException {
				OptionParser parser = new OptionParser();
				parser.posixlyCorrect(true);

				OptionSpec<Void> help = parser.accepts("help").forHelp();
				OptionSpec<Path> gff = parser.accepts("gff")
						.withRequiredArg().withValuesConvertedBy(
								new PathConverter(PathProperties.FILE_EXISTING));
				OptionSpec<Void> listContents = parser.accepts("list-contents");
				OptionSpec<Path> extractToDir = parser.accepts("extract-to-dir")
						.withRequiredArg().withValuesConvertedBy(new PathConverter());
				OptionSpec<Path> replaceWith = parser.accepts("replace-with")
						.withRequiredArg().withValuesConvertedBy(
								new PathConverter(PathProperties.FILE_EXISTING));
				OptionSpec<String> tag = parser.accepts("tag")
						.withRequiredArg().ofType(String.class);
				OptionSpec<Integer> number = parser.accepts("number")
						.withRequiredArg().ofType(Integer.class);

				OptionSet optionSet = parser.parse(args);

				return new Options(
						optionSet.valueOfOptional(gff),
						optionSet.has(listContents),
						optionSet.valueOfOptional(extractToDir),
						optionSet.valueOfOptional(replaceWith),
						optionSet.valueOfOptional(tag),
						optionSet.valueOfOptional(number)) {
					@Override
					public boolean isHelpRequested() {
						return optionSet.has(help);
					}
				};
			}

			@Override
			public boolean isUsageValid(Options options) {
				int commandCount = (options.listContents ? 1 : 0)
						+ (options.extractToDir.isPresent() ? 1 : 0)
						+ (options.replaceWith.isPresent() ? 1 : 0);

				// tag and/or number only with replace-with
				boolean areReplaceWithOptionsCorrect = !(options.replaceWith.isPresent()
						^ (options.tag.isPresent() || options.number.isPresent()));

				return options.gff.isPresent() && commandCount == 1 && areReplaceWithOptionsCorrect;
			}

			@Override
			public void printUsage() {
				System.out.println("To list the resources in a GFF:");
				System.out.println("  java -jar gff-tool.jar --gff=<gffFile> --list-contents");
				System.out.println("");
				System.out.println("To extract all resources in a GFF into a directory:");
				System.out.println("  java -jar gff-tool.jar --gff=<gffFile> "
						+ "--extract-to-dir=<dir>");
				System.out.println("");
				System.out.println("To replace a resource in a GFF:");
				System.out.println("  java -jar gff-tool.jar --gff=<gffFile> "
						+ "--replace-with=<file> --tag=TAG --number=n");
			}
		}

		final Optional<Path> gff;
		final boolean listContents;
		final Optional<Path> extractToDir;
		final Optional<Path> replaceWith;
		final Optional<String> tag;
		final Optional<Integer> number;

		Options(Optional<Path> gff,
				boolean listContents,
				Optional<Path> extractToDir,
				Optional<Path> replaceWith,
				Optional<String> tag,
				Optional<Integer> number) {
			this.gff = gff;
			this.listContents = listContents;
			this.extractToDir = extractToDir;
			this.replaceWith = replaceWith;
			this.tag = tag;
			this.number = number;
		}
	}

	private static final PrintStream OUT = System.out;

	public static void main(String[] args) throws IOException, URISyntaxException {
		Options options = new Options.Processor().process(args);

		Path gffPath = options.gff.get();

		byte[] bytes = Files.readAllBytes(gffPath);
		GffFile gffFile = GffFile.create(bytes);

		if (options.listContents) {
			listContents(gffFile, bytes.length);
		}

		if (options.extractToDir.isPresent()) {
			extractAllResources(gffFile, options.extractToDir.get());
		}

		if (options.replaceWith.isPresent()) {
			byte[] replacement = Files.readAllBytes(options.replaceWith.get());

			byte[] bytesWithReplacement = gffFile.replaceResource(
					options.tag.get(), options.number.get(), replacement);

			Files.write(gffPath, bytesWithReplacement);
		}
	}

	private static void listContents(GffFile gffFile, int length) {
		List<ResourceDescriptor> descriptors = gffFile.describeResources();

		OUT.println("Listing contents.");
		OUT.println("  offset   tag  number size");
		OUT.println("  -------- ---- ------ --------");
		String resourceTemplate = "  0x%06X %4s  %5d %8d\n";
		String nonResourceTemplate = "  0x%06X .... ...... %8d\n";
		int lastEnd = 0;
		for (ResourceDescriptor descriptor : descriptors) {
			int offset = descriptor.offset;

			if (lastEnd < offset) {
				OUT.format(nonResourceTemplate, lastEnd, offset - lastEnd);
			}

			OUT.format(
					resourceTemplate, offset, descriptor.tag, descriptor.number, descriptor.size);

			lastEnd = offset + descriptor.size;
		}
		if (lastEnd < length) {
			OUT.format(nonResourceTemplate, lastEnd, length - lastEnd);
		}
	}

	private static void extractAllResources(GffFile gffFile, Path dirPath) throws IOException {
		OUT.format("Extracting all resources to directory %s.\n", dirPath);

		Files.createDirectories(dirPath);

		List<ResourceDescriptor> gffResources = gffFile.describeResources();

		ConcurrentMap<String, Integer> maxNumberDigitsForTag = new ConcurrentHashMap<>();
		for (ResourceDescriptor descriptor : gffResources) {
			maxNumberDigitsForTag.merge(
					descriptor.tag, String.valueOf(descriptor.number).length(), Math::max);
		}

		Map<String, String> templateForTag = maxNumberDigitsForTag.entrySet().stream()
				.collect(Collectors.toMap(
						Map.Entry::getKey,
						tagAndMaxDigits -> "%s-%0" + tagAndMaxDigits.getValue() + "d"));

		for (ResourceDescriptor descriptor : gffResources) {
			String template = templateForTag.get(descriptor.tag);
			String name = String.format(template, descriptor.tag, descriptor.number);
			byte[] resourceData = gffFile.getResourceData(descriptor.tag, descriptor.number);
			Files.write(dirPath.resolve(name), resourceData);
		}
	}
}
