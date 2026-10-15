package android.util;

/**
 * JVM stand-in for the platform base64 helper that the adapter uses.
 *
 * android.util.Base64 exists in android.jar, but only as a build-time stub: calling it on a plain
 * JVM throws "Stub!". The adapter uses the platform helper rather than java.util.Base64 because the
 * latter needs API 26 and this app supports API 24. The harness therefore compiles this small
 * delegate into its own classes directory, so the JVM exercises the real request bytes.
 */
public final class Base64 {
	public static final int NO_WRAP = 2;


	private Base64() {
	}


	public static String encodeToString(byte[] input, int flags) {
		return java.util.Base64.getEncoder().encodeToString(input);
	}


	public static byte[] decode(String input, int flags) {
		return java.util.Base64.getDecoder().decode(input);
	}
}
