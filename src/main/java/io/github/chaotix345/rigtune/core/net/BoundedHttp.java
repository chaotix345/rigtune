package io.github.chaotix345.rigtune.core.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

// HTTP bodies with a byte cap, a stall timeout and an overall deadline. HttpRequest.timeout() only covers the wait
// for response headers, so without these a stalled or endless body would block its thread forever.
public final class BoundedHttp {
	private static final long POLL_MILLIS = 100;

	public interface Sink {
		void write(ByteBuffer buffer) throws IOException;
	}

	public static final class Progress {
		private volatile long lastNanos = System.nanoTime();

		void touch() {
			lastNanos = System.nanoTime();
		}
	}

	private BoundedHttp() {
	}

	// Feeds the body to sink. Past maxBytes it fails, or with truncate it stops there and completes.
	public static HttpResponse.BodySubscriber<Void> capped(long maxBytes, boolean truncate, Progress progress, Sink sink) {
		return new Capped(maxBytes, truncate, progress, sink);
	}

	public static HttpResponse.BodySubscriber<byte[]> bytes(long maxBytes, boolean truncate, Progress progress) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		return HttpResponse.BodySubscribers.mapping(capped(maxBytes, truncate, progress, buffer -> {
			byte[] chunk = new byte[buffer.remaining()];
			buffer.get(chunk);
			out.write(chunk);
		}), ignored -> out.toByteArray());
	}

	// Sends and waits for the whole response, cancelling the exchange when no body bytes arrive for `stall`
	// or when it takes longer than `deadline` in total.
	public static <T> HttpResponse<T> send(HttpClient http, HttpRequest request, HttpResponse.BodyHandler<T> handler,
			Progress progress, Duration stall, Duration deadline) throws IOException {
		long start = System.nanoTime();
		CompletableFuture<HttpResponse<T>> future = http.sendAsync(request, handler);
		try {
			while (true) {
				try {
					return future.get(POLL_MILLIS, TimeUnit.MILLISECONDS);
				} catch (TimeoutException e) {
					long now = System.nanoTime();
					if (now - start > deadline.toNanos()) {
						throw new HttpTimeoutException(request.method() + " " + request.uri() + " took longer than " + deadline.toSeconds() + " s");
					}
					if (now - progress.lastNanos > stall.toNanos()) {
						throw new HttpTimeoutException(request.method() + " " + request.uri() + " stalled for " + stall.toMillis() + " ms");
					}
				}
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new InterruptedIOException("Interrupted: " + request.method() + " " + request.uri());
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			while (cause instanceof CompletionException && cause.getCause() != null) {
				cause = cause.getCause();
			}
			if (cause instanceof IOException io) {
				throw io;
			}
			throw new IOException(request.method() + " " + request.uri() + " failed: " + cause, cause);
		} finally {
			if (!future.isDone()) {
				future.cancel(true);
			}
		}
	}

	private static final class Capped implements HttpResponse.BodySubscriber<Void> {
		private final long maxBytes;
		private final boolean truncate;
		private final Progress progress;
		private final Sink sink;
		private final CompletableFuture<Void> body = new CompletableFuture<>();
		private Flow.Subscription subscription;
		private long received;

		Capped(long maxBytes, boolean truncate, Progress progress, Sink sink) {
			this.maxBytes = maxBytes;
			this.truncate = truncate;
			this.progress = progress;
			this.sink = sink;
		}

		@Override
		public void onSubscribe(Flow.Subscription s) {
			subscription = s;
			progress.touch();
			s.request(1);
		}

		@Override
		public void onNext(List<ByteBuffer> buffers) {
			if (body.isDone()) {
				return;
			}
			try {
				for (ByteBuffer buffer : buffers) {
					long room = maxBytes - received;
					if (buffer.remaining() > room) {
						if (!truncate) {
							fail(new IOException("Response body is larger than " + maxBytes + " bytes"));
							return;
						}
						buffer.limit(buffer.position() + (int) room);
						sink.write(buffer);
						received = maxBytes;
						subscription.cancel();
						body.complete(null);
						return;
					}
					received += buffer.remaining();
					sink.write(buffer);
				}
				progress.touch();
				subscription.request(1);
			} catch (IOException | RuntimeException e) {
				fail(e);
			}
		}

		private void fail(Throwable error) {
			subscription.cancel();
			body.completeExceptionally(error);
		}

		@Override
		public void onError(Throwable throwable) {
			body.completeExceptionally(throwable);
		}

		@Override
		public void onComplete() {
			body.complete(null);
		}

		@Override
		public CompletionStage<Void> getBody() {
			return body;
		}
	}
}
