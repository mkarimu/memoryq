package com.vstream.Vidiserver;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.function.LongSupplier;

/**
 * Tries the AI first (if there is a key) and falls back to the built-in summarizer for that story whenever
 * the AI is unavailable, fails, hits its daily cap or answers with generic filler. News keeps flowing with or
 * without a key, and the reason the AI was skipped is shown to the admin.
 */
public final class ChainSummarizer implements NewsService.Summarizer {

    private static final long AUTH_PAUSE_MS = 6L * 60 * 60 * 1000;
    private static final long LIMIT_PAUSE_MS = 30L * 60 * 1000;
    private static final long FAIL_PAUSE_MS = 10L * 60 * 1000;

    private final NewsService.Summarizer ai;
    private final NewsService.Summarizer builtIn;
    private final LongSupplier clock;
    private final int aiDailyCap;
    private String day = "";
    private int aiCalls = 0;
    private long pausedUntil = 0;
    private int failureStreak = 0;
    private String note = "";

    public ChainSummarizer(NewsService.Summarizer ai, NewsService.Summarizer builtIn, LongSupplier clock, int aiDailyCap) {
        this.ai = ai;
        this.builtIn = builtIn;
        this.clock = clock;
        this.aiDailyCap = aiDailyCap;
    }

    @Override
    public boolean ready() {
        return true;
    }

    @Override
    public boolean aiReady() {
        return ai.ready();
    }

    @Override
    public String model() {
        return ai.ready() ? ai.model() + " + built-in fallback" : "built-in";
    }

    @Override
    public synchronized String note() {
        if (!ai.ready()) {
            return "built-in summarizer: set OPENAI_API_KEY to add AI summaries";
        }
        rollDay();
        return "AI calls today: " + aiCalls + " / " + aiDailyCap + (note.isEmpty() ? "" : " - " + note);
    }

    @Override
    public NewsService.Summary summarize(String sourceName, String title, String description) throws IOException {
        if (ai.ready() && beginAiCall()) {
            try {
                NewsService.Summary s = ai.summarize(sourceName, title, description);
                if (s == null) {
                    succeeded();
                    return null;
                }
                if (ExtractiveSummarizer.looksGeneric(s.headline + " " + s.summary)) {
                    generic();
                } else {
                    succeeded();
                    return s;
                }
            } catch (NewsService.AuthException e) {
                paused(AUTH_PAUSE_MS, "AI key problem: " + NewsService.oneLine(e.getMessage(), 160));
            } catch (NewsService.LimitException e) {
                paused(LIMIT_PAUSE_MS, "AI limit or quota reached: " + NewsService.oneLine(e.getMessage(), 160));
            } catch (IOException e) {
                failed(NewsService.oneLine(e.getMessage(), 200));
            }
        }
        return builtIn.summarize(sourceName, title, description);
    }

    private void rollDay() {
        String today = Instant.ofEpochMilli(clock.getAsLong()).atZone(ZoneOffset.UTC).toLocalDate().toString();
        if (!today.equals(day)) {
            day = today;
            aiCalls = 0;
        }
    }

    private synchronized boolean beginAiCall() {
        rollDay();
        if (clock.getAsLong() < pausedUntil) {
            return false;
        }
        if (aiCalls >= aiDailyCap) {
            note = "the daily AI limit of " + aiDailyCap + " was reached; using the built-in summarizer until tomorrow";
            return false;
        }
        aiCalls++;
        return true;
    }

    private synchronized void succeeded() {
        failureStreak = 0;
        note = "";
    }

    private synchronized void generic() {
        note = "an AI answer looked like generic filler; the built-in summary was used for that story";
    }

    private synchronized void paused(long ms, String why) {
        pausedUntil = clock.getAsLong() + ms;
        note = why + "; using the built-in summarizer";
    }

    private synchronized void failed(String why) {
        failureStreak++;
        note = "AI skipped (" + why + "); using the built-in summarizer for this story";
        if (failureStreak >= 3) {
            pausedUntil = clock.getAsLong() + FAIL_PAUSE_MS;
            failureStreak = 0;
            note += " - AI paused for 10 minutes";
        }
    }
}