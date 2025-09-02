package tianci.dev.xptranslatetext.translate;

public class SpanSpec {
    public Object span;
    public int start;
    public int end;
    public int flags;

    public SpanSpec(Object span, int start, int end, int flags) {
        this.span = span;
        this.start = start;
        this.end = end;
        this.flags = flags;
    }
}
