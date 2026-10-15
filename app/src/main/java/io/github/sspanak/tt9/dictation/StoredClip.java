package io.github.sspanak.tt9.dictation;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Reads 16 kHz mono 16-bit WAV clips (from app assets or files) and encodes PCM as WAV.
 * Used by the dictation engine, the stored-clip test screen and the emulator test hook.
 */
public final class StoredClip {
	public static final int SAMPLE_RATE_HZ = 16_000;
	private static final int HEADER_BYTES = 44;
	private static final int BYTES_PER_SAMPLE = 2;

	private StoredClip() {
	}

	public static byte[] encodeWav(byte[] pcm, int sampleRate) {
		if (pcm == null || (pcm.length & 1) != 0) {
			throw new IllegalArgumentException("PCM16 data must contain complete samples");
		}
		byte[] wav = new byte[HEADER_BYTES + pcm.length];
		ByteBuffer header = ByteBuffer.wrap(wav, 0, HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
		header.put(ascii("RIFF"));
		header.putInt(36 + pcm.length);
		header.put(ascii("WAVE"));
		header.put(ascii("fmt "));
		header.putInt(16);
		header.putShort((short) 1);
		header.putShort((short) 1);
		header.putInt(sampleRate);
		header.putInt(sampleRate * BYTES_PER_SAMPLE);
		header.putShort((short) BYTES_PER_SAMPLE);
		header.putShort((short) 16);
		header.put(ascii("data"));
		header.putInt(pcm.length);
		System.arraycopy(pcm, 0, wav, HEADER_BYTES, pcm.length);
		return wav;
	}

	/** Returns the raw PCM16 samples of the "data" chunk. Rejects anything but 16 kHz mono 16-bit. */
	public static byte[] readPcm16Mono(InputStream in) throws IOException {
		byte[] all = readAll(in);
		if (all.length < 12 || !"RIFF".equals(asciiAt(all, 0, 4)) || !"WAVE".equals(asciiAt(all, 8, 4))) {
			throw new IOException("Not a WAV file");
		}

		ByteBuffer buf = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN);
		int channels = -1;
		int bits = -1;
		int rate = -1;
		int pos = 12;
		while (pos + 8 <= all.length) {
			String id = asciiAt(all, pos, 4);
			long size = buf.getInt(pos + 4) & 0xffffffffL;
			int body = pos + 8;
			if ("fmt ".equals(id) && body + 16 <= all.length) {
				channels = buf.getShort(body + 2) & 0xffff;
				rate = buf.getInt(body + 4);
				bits = buf.getShort(body + 14) & 0xffff;
			} else if ("data".equals(id)) {
				if (channels != 1 || bits != 16 || rate != SAMPLE_RATE_HZ) {
					throw new IOException("Clip must be 16 kHz mono 16-bit WAV");
				}
				int length = (int) Math.min(size, all.length - body);
				byte[] pcm = new byte[length & ~1];
				System.arraycopy(all, body, pcm, 0, pcm.length);
				return pcm;
			}
			pos = body + (int) size + (int) (size & 1);
		}
		throw new IOException("WAV has no data chunk");
	}

	/** Largest absolute sample value, 0 to 32768. */
	public static int peak(byte[] pcm) {
		int peak = 0;
		for (int i = 0; i + 1 < pcm.length; i += 2) {
			int sample = (short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8));
			int magnitude = Math.abs(sample);
			if (magnitude > peak) {
				peak = magnitude;
			}
		}
		return peak;
	}

	private static byte[] ascii(String value) {
		return value.getBytes(StandardCharsets.US_ASCII);
	}

	private static String asciiAt(byte[] bytes, int offset, int length) {
		return new String(bytes, offset, length, StandardCharsets.US_ASCII);
	}

	private static byte[] readAll(InputStream in) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try {
			byte[] chunk = new byte[8192];
			int count;
			while ((count = in.read(chunk)) != -1) {
				out.write(chunk, 0, count);
			}
		} finally {
			in.close();
		}
		return out.toByteArray();
	}
}
