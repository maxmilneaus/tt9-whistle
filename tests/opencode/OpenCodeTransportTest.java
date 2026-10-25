package io.github.sspanak.tt9.dictation;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import io.github.sspanak.tt9.dictation.OpenCodeTranscriber.ApiException;

/**
 * End-to-end transport contract against a loopback HTTPS server with a self-signed certificate.
 *
 * Everything here is local: a fake credential, a fake endpoint and canned responses. No live
 * provider, no phone and no audio device is involved.
 */
final class OpenCodeTransportTest {
	private static final String FAKE_KEY = "FAKE-LOOPBACK-KEY";
	private static final byte[] WAV = Fixtures.wav(Fixtures.SAMPLES);

	private static volatile int status = 200;
	private static volatile String response = "";
	private static volatile String location = null;
	private static volatile long delayMs = 0;
	private static volatile CountDownLatch arrived = new CountDownLatch(0);
	private static final AtomicInteger primaryRequests = new AtomicInteger();
	private static final AtomicInteger redirectRequests = new AtomicInteger();
	private static final AtomicReference<String> authorization = new AtomicReference<>();
	private static final AtomicReference<String> userAgent = new AtomicReference<>();
	private static final AtomicReference<String> session = new AtomicReference<>();
	private static final AtomicReference<String> wireBody = new AtomicReference<>();


	private OpenCodeTransportTest() {
	}


	public static void main(String[] args) throws Exception {
		if (args.length < 1) {
			System.out.println("usage: OpenCodeTransportTest <loopback-keystore.jks>");
			System.exit(2);
		}

		HttpsServer server = startServer(args[0]);
		String endpoint = "https://localhost:" + server.getAddress().getPort() + "/chat/completions";

		try {
			successfulRoundTrip(endpoint);
			statusMapping(endpoint);
			redirects(endpoint);
			oversizedResponse(endpoint);
			cancellation(endpoint);
			timeouts();
			credentials();
		} catch (Exception error) {
			WhistleCheck.fail("unexpected failure: " + error);
		} finally {
			server.stop(0);
		}

		WhistleCheck.finish("OpenCodeTransport");
	}


	private static void successfulRoundTrip(String endpoint) throws Exception {
		reset();
		status = 200;
		response = Fixtures.reply("Could you call me tomorrow?");

		String text = transcriber(endpoint).transcribe(WAV);

		WhistleCheck.equal("Could you call me tomorrow?", text, "the transcript comes back from the service");
		WhistleCheck.equal("Bearer " + FAKE_KEY, authorization.get(), "the trimmed key is sent as a bearer credential");
		WhistleCheck.equal(OpenCodeTranscriber.USER_AGENT, userAgent.get(), "the client identifies itself");
		WhistleCheck.yes(session.get() != null && !session.get().isEmpty(), "a session header is sent");
		WhistleCheck.equal(1, primaryRequests.get(), "exactly one request is made");
		WhistleCheck.yes(
			wireBody.get() != null && wireBody.get().contains("data:audio/wav;base64,"),
			"the audio travels as an inlined WAV data URI"
		);
		WhistleCheck.yes(
			wireBody.get() != null && !wireBody.get().contains(FAKE_KEY),
			"the wire body never carries the key"
		);
	}


	private static void statusMapping(String endpoint) throws Exception {
		int[] statuses = {401, 403, 429, 413, 408, 504, 500, 400, 302};
		ApiException.Reason[] reasons = {
			ApiException.Reason.UNAUTHORIZED,
			ApiException.Reason.UNAUTHORIZED,
			ApiException.Reason.BUSY,
			ApiException.Reason.TOO_LARGE,
			ApiException.Reason.TIMEOUT,
			ApiException.Reason.TIMEOUT,
			ApiException.Reason.SERVER,
			ApiException.Reason.REJECTED,
			ApiException.Reason.REJECTED
		};

		for (int i = 0; i < statuses.length; i++) {
			reset();
			status = statuses[i];
			response = "{}";

			ApiException failure = failure(transcriber(endpoint), WAV);
			WhistleCheck.equal(
				reasons[i],
				failure == null ? null : failure.reason,
				"HTTP " + statuses[i] + " is reported as " + reasons[i]
			);
			WhistleCheck.yes(
				failure != null && !String.valueOf(failure.getMessage()).contains(FAKE_KEY),
				"HTTP " + statuses[i] + " failures never echo the key"
			);
		}
	}


	private static void redirects(String endpoint) throws Exception {
		reset();
		status = 302;
		location = "https://localhost/elsewhere";
		response = "";

		ApiException failure = failure(transcriber(endpoint), WAV);

		WhistleCheck.equal(
			ApiException.Reason.REJECTED,
			failure == null ? null : failure.reason,
			"a redirect is refused instead of followed"
		);
		WhistleCheck.equal(0, redirectRequests.get(), "the credential is never resent to a redirect target");
		WhistleCheck.equal(1, primaryRequests.get(), "the request is attempted once");
	}


	private static void oversizedResponse(String endpoint) throws Exception {
		reset();
		status = 200;
		response = repeat('x', 300 * 1024);

		ApiException failure = failure(transcriber(endpoint), WAV);

		WhistleCheck.equal(
			ApiException.Reason.TOO_LARGE,
			failure == null ? null : failure.reason,
			"a response beyond 256 KB is refused"
		);
	}


	private static void cancellation(String endpoint) throws Exception {
		reset();
		status = 200;
		response = Fixtures.reply("This text must never be used");
		delayMs = 5_000;
		arrived = new CountDownLatch(1);
		OpenCodeTranscriber transcriber = transcriber(endpoint);

		OpenCodeTranscriber inFlight = transcriber(endpoint);
		AtomicReference<Throwable> outcome = new AtomicReference<>();
		Thread worker = new Thread(() -> {
			try {
				inFlight.transcribe(WAV);
			} catch (Throwable error) {
				outcome.set(error);
			}
		}, "harness-transcribe");
		worker.setDaemon(true);
		worker.start();

		WhistleCheck.yes(arrived.await(10, TimeUnit.SECONDS), "the request reached the service before cancelling");
		inFlight.cancel();
		worker.join(10_000);
		WhistleCheck.yes(!worker.isAlive(), "cancelling releases the waiting caller");

		Throwable failure = outcome.get();
		WhistleCheck.yes(failure instanceof ApiException, "a cancelled request fails with the adapter's own exception");
		WhistleCheck.equal(
			ApiException.Reason.CANCELLED,
			failure instanceof ApiException ? ((ApiException) failure).reason : null,
			"cancelling is reported as cancelled"
		);

		reset();
		status = 200;
		response = Fixtures.reply("A later attempt on the same instance");
		ApiException refused = failure(inFlight, WAV);
		WhistleCheck.equal(
			ApiException.Reason.CANCELLED,
			refused == null ? null : refused.reason,
			"a cancelled instance stays cancelled for later calls"
		);
		WhistleCheck.equal(0, primaryRequests.get(), "a cancelled instance sends no follow-up request");

		// The controller stores the transcriber under its lock, releases the lock and only then calls
		// transcribe(). cancel() can run in that window, before transcribe() has been entered at all.
		reset();
		status = 200;
		response = Fixtures.reply("This text must never be sent");
		OpenCodeTranscriber cancelledUpFront = transcriber(endpoint);
		cancelledUpFront.cancel();

		ApiException cancelled = failure(cancelledUpFront, WAV);
		WhistleCheck.equal(
			ApiException.Reason.CANCELLED,
			cancelled == null ? null : cancelled.reason,
			"a cancel before transcribe() is honoured"
		);
		WhistleCheck.equal(0, primaryRequests.get(), "no connection is opened after an early cancel");
		WhistleCheck.equal(0, redirectRequests.get(), "nothing is sent anywhere after an early cancel");
		WhistleCheck.yes(wireBody.get() == null, "no request body is written after an early cancel");
	}


	private static void timeouts() {
		WhistleCheck.equal(60_000L, OpenCodeTranscriber.OVERALL_TIMEOUT_MS, "the overall request budget is unchanged");
		WhistleCheck.equal(10_000, OpenCodeTranscriber.CONNECT_TIMEOUT_MS, "the connect timeout is unchanged");
		WhistleCheck.equal(60_000, OpenCodeTranscriber.READ_TIMEOUT_MS, "the read timeout is unchanged");
		WhistleCheck.equal(256 * 1024, OpenCodeTranscriber.MAX_RESPONSE_BYTES, "responses stay bounded to 256 KB");
	}


	private static void credentials() {
		WhistleCheck.checkThrows(
			ApiException.class,
			() -> OpenCodeTranscriber.checkedHttpsEndpoint("http://localhost/chat/completions"),
			"plain HTTP endpoints are refused before any connection"
		);
		WhistleCheck.checkThrows(
			ApiException.class,
			() -> OpenCodeTranscriber.checkedHttpsEndpoint("https://user:secret@localhost/chat/completions"),
			"endpoints with embedded credentials are refused"
		);
		WhistleCheck.checkThrows(
			ApiException.class,
			() -> OpenCodeTranscriber.checkedHttpsEndpoint("not a url"),
			"malformed endpoints are refused"
		);

		Throwable failure = WhistleCheck.thrown(
			() -> OpenCodeTranscriber.checkedHttpsEndpoint("https://user:secret@localhost/chat/completions")
		);
		WhistleCheck.yes(
			failure != null && !String.valueOf(failure.getMessage()).contains("secret"),
			"endpoint failures never echo the embedded credentials"
		);
	}


	private static HttpsServer startServer(String keystorePath) throws Exception {
		KeyStore keys = KeyStore.getInstance("JKS");
		try (InputStream input = Files.newInputStream(Paths.get(keystorePath))) {
			keys.load(input, "android".toCharArray());
		}

		KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
		keyManagers.init(keys, "android".toCharArray());
		TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
		trustManagers.init(keys);
		SSLContext ssl = SSLContext.getInstance("TLS");
		ssl.init(keyManagers.getKeyManagers(), trustManagers.getTrustManagers(), null);
		HttpsURLConnection.setDefaultSSLSocketFactory(ssl.getSocketFactory());

		HttpsServer server = HttpsServer.create(new InetSocketAddress("localhost", 0), 0);
		server.setHttpsConfigurator(new HttpsConfigurator(ssl));
		server.setExecutor(Executors.newCachedThreadPool(runnable -> {
			Thread thread = new Thread(runnable);
			thread.setDaemon(true);
			return thread;
		}));

		server.createContext("/chat/completions", exchange -> {
			primaryRequests.incrementAndGet();
			authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
			userAgent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
			session.set(exchange.getRequestHeaders().getFirst("x-opencode-session"));
			wireBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			arrived.countDown();

			if (delayMs > 0) {
				try {
					Thread.sleep(delayMs);
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
				}
			}
			if (location != null) {
				exchange.getResponseHeaders().add("Location", location);
			}

			try {
				byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
				exchange.sendResponseHeaders(status, bytes.length);
				try (OutputStream output = exchange.getResponseBody()) {
					output.write(bytes);
				}
			} catch (IOException disconnected) {
				// Expected when the harness cancels a request that is already in flight.
			}
		});

		server.createContext("/elsewhere", exchange -> {
			redirectRequests.incrementAndGet();
			byte[] bytes = new byte[0];
			try {
				exchange.sendResponseHeaders(500, bytes.length);
				exchange.close();
			} catch (IOException closed) {
				// The redirect target must never be reached; this branch only keeps the server quiet.
			}
		});

		server.start();
		return server;
	}


	private static OpenCodeTranscriber transcriber(String endpoint) throws Exception {
		OpenCodeSettings settings = new OpenCodeSettings(new Fakes.Storage(), new Fakes.KeyProtector());
		settings.save(endpoint, "test-model", "Clean this up.", "\n  " + FAKE_KEY + "  \n");
		return new OpenCodeTranscriber(settings);
	}


	private static ApiException failure(OpenCodeTranscriber transcriber, byte[] wav) {
		Throwable thrown = WhistleCheck.thrown(() -> transcriber.transcribe(wav));
		if (thrown instanceof ApiException) {
			return (ApiException) thrown;
		}
		WhistleCheck.fail("expected the adapter's own exception, got " + thrown);
		return null;
	}


	private static void reset() {
		status = 200;
		response = "";
		location = null;
		delayMs = 0;
		arrived = new CountDownLatch(0);
		primaryRequests.set(0);
		redirectRequests.set(0);
		authorization.set(null);
		userAgent.set(null);
		session.set(null);
		wireBody.set(null);
	}


	private static String repeat(char character, int count) {
		StringBuilder text = new StringBuilder(count);
		for (int i = 0; i < count; i++) {
			text.append(character);
		}
		return text.toString();
	}
}
