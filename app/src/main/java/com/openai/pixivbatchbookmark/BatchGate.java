package com.openai.pixivbatchbookmark;

import java.net.URI;

/** Accepts one page completion and one result for the current operation only. */
public final class BatchGate {
    private long sequence = 0;
    private String kind, id, url;
    private int phase = 0; // 0 idle, 1 loading, 2 executing

    public BatchGate() {}
    public BatchGate(long initialSequence) { sequence = initialSequence; }

    public long begin(String kind, String id, String url) {
        this.kind = kind;
        this.id = id;
        this.url = url;
        phase = 1;
        return ++sequence;
    }

    public boolean pageReady(String actualUrl) { return pageReady(sequence, actualUrl); }

    public boolean pageReady(long navigationToken, String actualUrl) {
        if (navigationToken != sequence || phase != 1 || !samePage(url, actualUrl)) return false;
        phase = 2;
        return true;
    }

    public boolean acceptsPageError(long navigationToken, String actualUrl) {
        return navigationToken == sequence && phase != 0 && samePage(url, actualUrl);
    }

    public boolean complete(long token, String kind, String id) {
        if (phase != 2 || token != sequence || !this.kind.equals(kind) || !this.id.equals(id)) return false;
        phase = 0;
        return true;
    }

    public boolean isActive(long token) { return phase != 0 && token == sequence; }
    /** Shared production decision for delayed evaluateJavascript start callbacks. */
    public boolean shouldRejectQueueStart(long capturedToken, boolean running, boolean queueActive, String result) {
        return running && queueActive && phase == 2 && capturedToken == sequence && !"true".equals(result);
    }
    public void cancel() { phase = 0; sequence++; }

    private boolean samePage(String expected, String actual) {
        try {
            URI a = new URI(expected), b = new URI(actual);
            if (!"https".equals(b.getScheme()) || !"www.pixiv.net".equals(b.getHost())) return false;
            if (b.getPort() != -1 && b.getPort() != 443) return false;
            String ap = a.getPath(), bp = b.getPath();
            if (ap == null || ap.isEmpty()) ap = "/";
            if (bp == null || bp.isEmpty()) bp = "/";
            return ap.equals(bp);
        } catch (Exception ignored) { return false; }
    }
}
