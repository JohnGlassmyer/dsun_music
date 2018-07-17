package net.johnglassmyer.dsun.common.options;

import joptsimple.OptionException;

public abstract class OptionsProcessor<O extends OptionsWithHelp> {
	private static final int ERROR_EXIT_VALUE = -0xDEADBEEF;

	public abstract O parseArgs(String[] args) throws OptionException;

	public abstract boolean isUsageValid(O options);

	public abstract void printUsage();

	public O process(String[] args) {
		O options = null;
		try {
			options = parseArgs(args);
		} catch (OptionException oe) {
			System.err.println(oe.getMessage());
			if (oe.getCause() != null) {
				System.err.println(oe.getCause().getMessage());
			}

			System.exit(ERROR_EXIT_VALUE);
		}

		if (options.isHelpRequested()) {
			printUsage();

			System.exit(0);
		}

		if (!isUsageValid(options)) {
			printUsage();

			System.exit(ERROR_EXIT_VALUE);
		}

		return options;
	}
}
