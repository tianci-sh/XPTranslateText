package tianci.dev.xptranslatetext.translate;

import java.util.ArrayList;
import java.util.List;

/**
 * Text segment with original and translated content, along with
 * span specifications to preserve styling across translation.
 */
public class Segment {
    /** Absolute start in the original text. */
    public int start;
    /** Absolute end in the original text (exclusive). */
    public int end;
    /** Original text content of this segment. */
    public String text;
    /** Translated text, when available; falls back to {@link #text}. */
    public String translatedText;

    /** Spans relative to the segment. */
    public List<SpanSpec> spans = new ArrayList<>();

    public Segment(int start, int end, String text) {
        this.start = start;
        this.end = end;
        this.text = text;
    }
}
