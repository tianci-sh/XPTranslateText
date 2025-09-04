package tianci.dev.xptranslatetext.translate;

/**
 * Span specification for a text segment, preserving the span object and
 * its relative range within the segment.
 */
public class SpanSpec {
    /** Original span object applied to the text. */
    public Object span;
    /** Start offset relative to the segment start (inclusive). */
    public int start;
    /** End offset relative to the segment start (exclusive). */
    public int end;
    /** Span flags as in {@link android.text.Spanned}. */
    public int flags;

    public SpanSpec(Object span, int start, int end, int flags) {
        this.span = span;
        this.start = start;
        this.end = end;
        this.flags = flags;
    }
}
