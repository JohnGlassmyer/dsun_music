package net.johnglassmyer.dsun.common;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.function.Consumer;
import java.util.function.Function;

public interface UncheckedIo {
	public interface CheckedIoConsumer<T> {
		void accept(T t) throws IOException;
	}

	public interface CheckedIoFunction<U, V> {
		V apply(U u) throws IOException;
	}

	public interface CheckedIoRunnable {
		void run() throws IOException;
	}

	public interface CheckedIoSupplier<T> {
		T get() throws IOException;
	}

	public static <T> Consumer<T> uncheckConsumerIo(CheckedIoConsumer<T> checkedConsumer) {
		return t -> {
			try {
				checkedConsumer.accept(t);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		};
	}

	public static <U, V> Function<U, V> uncheckFunctionIo(CheckedIoFunction<U, V> checkedFunction) {
		return u -> {
			try {
				return checkedFunction.apply(u);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		};
	}

	public static <T> T uncheckSupplierIo(CheckedIoSupplier<T> checkedSupplier) {
		try {
			return checkedSupplier.get();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public static void uncheckRunnableIo(CheckedIoRunnable checkedRunnable) {
		try {
			checkedRunnable.run();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
