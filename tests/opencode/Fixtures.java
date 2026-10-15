package io.github.sspanak.tt9.dictation;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Deterministic audio bytes for the JVM harness; no recording and no device involved. */
final class Fixtures {
	static final byte[] SAMPLES = {0, 0, 1, 0, -1, -1, 32, 0, -32, -1};


	private Fixtures() {
	}


	/** A canonical 44-byte RIFF/WAVE header with 16-bit mono PCM, the shape the recorder produces. */
	static byte[] wav(byte[] pcm) {
		ByteBuffer buffer = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
		buffer.put("RIFF".getBytes(StandardCharsets.US_ASCII));
		buffer.putInt(36 + pcm.length);
		buffer.put("WAVE".getBytes(StandardCharsets.US_ASCII));
		buffer.put("fmt ".getBytes(StandardCharsets.US_ASCII));
		buffer.putInt(16);
		buffer.putShort((short) 1);
		buffer.putShort((short) 1);
		buffer.putInt(16000);
		buffer.putInt(32000);
		buffer.putShort((short) 2);
		buffer.putShort((short) 16);
		buffer.put("data".getBytes(StandardCharsets.US_ASCII));
		buffer.putInt(pcm.length);
		buffer.put(pcm);
		return buffer.array();
	}
}
