package tianci.dev.xptranslatetext;

import java.util.ArrayList;
import java.util.List;

class Segment {
    public int start;
    public int end;
    public String text;
    public String translatedText;

    public List<SpanSpec> spans = new ArrayList<>();

    public Segment(int start, int end, String text) {
        this.start = start;
        this.end = end;
        this.text = text;
    }
}
