package net.johnglassmyer.dsun.common.image;

import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.function.Supplier;

import com.google.common.base.Suppliers;

public class ImageFrame {
	public final int width;
	public final int height;
	private final Supplier<BitSet> alphaMaskSupplier;
	private final Supplier<byte[]> pixelsSupplier;
	private final NavigableMap<Integer, List<PixelRun>> runsByRow;

	public ImageFrame(int width, int height, NavigableMap<Integer, List<PixelRun>> runsByRow) {
		this.width = width;
		this.height = height;
		this.runsByRow = runsByRow;
		this.alphaMaskSupplier = Suppliers.memoize(this::composeAlphaBitmask)::get;
		this.pixelsSupplier = Suppliers.memoize(this::composePixels)::get;
	}

	public BitSet getAlphaMask() {
		return alphaMaskSupplier.get();
	}

	public byte[] getPixels() {
		return pixelsSupplier.get();
	}

	private BitSet composeAlphaBitmask() {
		BitSet alphaBitmask = new BitSet(width * height);

		for (Map.Entry<Integer, List<PixelRun>> yAndRuns : runsByRow.entrySet()) {
			int rowStart = yAndRuns.getKey() * width;
			for (PixelRun run : yAndRuns.getValue()) {
				int runStart = rowStart + run.startX;
				alphaBitmask.set(runStart, runStart + run.pixels.length);
			}
		}

		return alphaBitmask;
	}

	private byte[] composePixels() {
		byte[] pixels = new byte[height * width];

		for (Map.Entry<Integer, List<PixelRun>> yAndRuns : runsByRow.entrySet()) {
			int rowStart = yAndRuns.getKey() * width;
			for (PixelRun run : yAndRuns.getValue()) {
				System.arraycopy(run.pixels, 0, pixels, rowStart + run.startX, run.pixels.length);
			}
		}

		return pixels;
	}
}
