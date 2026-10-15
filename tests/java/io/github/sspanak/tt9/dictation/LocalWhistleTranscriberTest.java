package io.github.sspanak.tt9.dictation;

public final class LocalWhistleTranscriberTest {
    public static void main(String[] args) throws Exception {
        equal("hello", LocalWhistleTranscriber.parseLastLine("loading\n{\"text\":\"hello\",\"ttft_ms\":12}"));
        equal("", LocalWhistleTranscriber.parseLastLine("{\"text\":\"\",\"language\":\"\"}"));
        equal("Thanks, Max.\nNext line", LocalWhistleTranscriber.parseLastLine("{\"text\":\"Thanks, Max.\\nNext line\"}"));
        equal("café", LocalWhistleTranscriber.parseLastLine("{\"text\":\"caf\\u00e9\"}"));
        boolean rejected = false;
        try { LocalWhistleTranscriber.parseLastLine("not JSON"); }
        catch (java.io.IOException expected) { rejected = true; }
        if (!rejected) throw new AssertionError("Malformed engine response must fail");
        System.out.println("PASS: transcript, no-speech, escaping, Unicode, malformed output");
    }
    private static void equal(String expected, String actual) {
        if (!expected.equals(actual)) throw new AssertionError("Expected: " + expected + "; actual: " + actual);
    }
}
