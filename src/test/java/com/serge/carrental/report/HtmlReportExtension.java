package com.serge.carrental.report;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal JUnit 5 extension that captures:
 *  - per-test status (PASSED/FAILED/ABORTED)
 *  - per-test steps (added via HtmlReportExtension.step("..."))
 *  - optional @TestDescription text
 *
 * Generates: target/site/integration-tests.html
 */
public class HtmlReportExtension implements BeforeTestExecutionCallback,
        AfterTestExecutionCallback, TestWatcher, AfterAllCallback {

    public enum Status { PASSED, FAILED, ABORTED }

    private static final String OUTPUT_DIR = "target/site";
    private static final String OUTPUT_FILE = "integration-tests.html";

    private static final ThreadLocal<List<String>> CURRENT_STEPS =
            ThreadLocal.withInitial(ArrayList::new);

    private static final Map<String, TestEntry> TESTS = new ConcurrentHashMap<>();
    private static final List<String> ORDER = Collections.synchronizedList(new ArrayList<>());

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ISO_INSTANT;

    /** Call this from tests to record a visible step. */
    public static void step(String message) {
        CURRENT_STEPS.get().add(message);
    }

    @Override
    public void beforeTestExecution(ExtensionContext context) {
        String id = context.getUniqueId();
        TestEntry e = new TestEntry();
        e.uniqueId = id;
        e.displayName = context.getDisplayName();
        e.className = context.getRequiredTestClass().getSimpleName();
        e.methodName = context.getRequiredTestMethod().getName();
        e.description = findDescription(context).orElse("");
        e.start = Instant.now();
        TESTS.put(id, e);
        ORDER.add(id);
        CURRENT_STEPS.set(new ArrayList<>());
    }

    @Override
    public void afterTestExecution(ExtensionContext context) {
        TestEntry e = TESTS.get(context.getUniqueId());
        if (e != null) {
            e.end = Instant.now();
            e.steps = new ArrayList<>(CURRENT_STEPS.get());
        }
        CURRENT_STEPS.remove();
    }

    @Override
    public void testSuccessful(ExtensionContext context) {
        setStatus(context, Status.PASSED, null);
    }

    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
        setStatus(context, Status.FAILED, cause);
    }

    @Override
    public void testAborted(ExtensionContext context, Throwable cause) {
        setStatus(context, Status.ABORTED, cause);
    }

    private void setStatus(ExtensionContext ctx, Status s, Throwable t) {
        TestEntry e = TESTS.get(ctx.getUniqueId());
        if (e != null) {
            e.status = s;
            if (t != null) e.error = t.toString();
        }
    }

    private Optional<String> findDescription(ExtensionContext ctx) {
        return Optional.ofNullable(ctx.getRequiredTestMethod().getAnnotation(TestDescription.class))
                .map(TestDescription::value);
    }

    @Override
    public void afterAll(ExtensionContext context) throws Exception {
        // Only write once per JVM run; if multiple classes use the extension,
        // we still aggregate all collected tests so far.
        writeHtmlReport();
    }

    private void writeHtmlReport() {
        try {
            Files.createDirectories(Path.of(OUTPUT_DIR));
            Path file = Path.of(OUTPUT_DIR, OUTPUT_FILE);
            String html = buildHtml();
            Files.writeString(file, html, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            System.out.println("HTML test report written to: " + file.toAbsolutePath());
        } catch (IOException e) {
            System.err.println("Failed to write HTML report: " + e);
        }
    }

    private String buildHtml() {
        StringBuilder sb = new StringBuilder(32_000);
        Map<Status, Long> counts = summarize();
        long total = ORDER.size();
        long passed = counts.getOrDefault(Status.PASSED, 0L);
        long failed = counts.getOrDefault(Status.FAILED, 0L);
        long aborted = counts.getOrDefault(Status.ABORTED, 0L);
        long totalMs = 0L;
        for (String id : ORDER) {
            TestEntry e = TESTS.get(id);
            if (e != null && e.start != null && e.end != null) {
                totalMs += Duration.between(e.start, e.end).toMillis();
            }
        }
        String totalDurHuman = human(Duration.ofMillis(totalMs));

        sb.append("""
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8"/>
  <meta name="viewport" content="width=device-width, initial-scale=1"/>
  <title>Integration Tests Report</title>
  <style>
    :root{
                                      --bg: #0b0c10;
                                      --panel:#0f1117;
                                      --text:#1f2937;
                                      --subtle:#6b7280;
                                      --border:#e5e7eb;
                                      --muted-bg:#f9fafb;
                                      --ok-bg:#e8f7ee; --ok-fg:#0f7b3e; --ok-br:#a7e0bf;
                                      --fail-bg:#fdeaea; --fail-fg:#b01e1e; --fail-br:#f1b2b2;
                                      --ab-bg:#fff7e6; --ab-fg:#9a6700; --ab-br:#f0d48a;
                                      --accent: #2563eb;
                                      --ring: rgba(37,99,235,.15);
                                      --card:#ffffff;
                                    }
@media (prefers-color-scheme: dark) {
      :root{
        --bg:#0b0c10;
        --card:#0f1117;
        --text:#e5e7eb;
        --subtle:#9da3af;
        --border:#1f2430;
        --muted-bg:#111827;
        --ok-bg:#0f2b1d; --ok-fg:#6ee7b7; --ok-br:#134e4a;
        --fail-bg:#2a1111; --fail-fg:#fca5a5; --fail-br:#7f1d1d;
        --ab-bg:#2a230f; --ab-fg:#fbbf24; --ab-br:#713f12;
        --ring: rgba(59,130,246,.25);
      }
    }
    *{box-sizing:border-box}
    body{
      font-family: system-ui, -apple-system, Segoe UI, Roboto, Arial, sans-serif;
      margin:0;
      color: var(--text);
      background:
        radial-gradient(1200px 600px at 10% -10%, rgba(37,99,235,.15), transparent 60%),
        radial-gradient(1200px 600px at 110% 10%, rgba(16,185,129,.12), transparent 60%),
        var(--bg);
    }
    .container{max-width:1100px;margin:32px auto;padding:0 20px;}
    .header{display:flex;justify-content:space-between;gap:16px;align-items:flex-end;flex-wrap:wrap}
    h1{margin:0;font-size:28px;letter-spacing:.2px}
    .meta{color:var(--subtle);margin-top:6px}
    .cards{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px;margin-top:16px}
    .card{background:var(--card);border:1px solid var(--border);border-radius:14px;padding:12px 14px;box-shadow:0 1px 2px rgba(0,0,0,.04)}
    .card .k{font-size:12px;color:var(--subtle);letter-spacing:.3px;text-transform:uppercase}
    .card .v{font-size:22px;font-weight:700;margin-top:4px}
    .progress{height:10px;background:var(--muted-bg);border:1px solid var(--border);border-radius:999px;overflow:hidden}
    .bar{height:100%;background:linear-gradient(90deg,#22c55e,#16a34a);}
    .toolbar{display:flex;gap:8px;flex-wrap:wrap;margin-top:16px}
    .input, .select{
      background:var(--card); border:1px solid var(--border); border-radius:10px;
      padding:8px 10px; color:var(--text); outline:none; min-width:220px;
    }
    .input:focus, .select:focus{box-shadow:0 0 0 6px var(--ring); border-color: var(--accent);}
    table{border-collapse:separate;border-spacing:0;width:100%;margin-top:12px;background:var(--card);border:1px solid var(--border);border-radius:14px;overflow:hidden}
    thead th{
      position:sticky;top:0;background:var(--muted-bg);text-align:left;padding:10px;border-bottom:1px solid var(--border);
      font-size:13px;user-select:none;cursor:pointer;
    }
    thead th[data-sort]:after{content:" ↕";opacity:.5;font-weight:400}
    thead th[data-sort][data-dir="asc"]:after{content:" ↑"}
    thead th[data-sort][data-dir="desc"]:after{content:" ↓"}
    td{padding:10px;vertical-align:top;border-bottom:1px solid var(--border);}
    tbody tr:nth-child(even){background:rgba(0,0,0,.02)}
    tbody tr:hover{background:rgba(37,99,235,.06)}
    .mono{font-family:ui-monospace,SFMono-Regular,Consolas,Menlo,monospace;font-size:12.5px}
    .muted{color:var(--subtle);}
    .badge{display:inline-block;padding:2px 10px;border-radius:999px;font-size:12px;font-weight:700;border:1px solid transparent;}
    .ok{background:var(--ok-bg);color:var(--ok-fg);border-color:var(--ok-br)}
    .fail{background:var(--fail-bg);color:var(--fail-fg);border-color:var(--fail-br)}
    .aborted{background:var(--ab-bg);color:var(--ab-fg);border-color:var(--ab-br)}
    .details{margin-top:22px;display:grid;gap:12px}
    details{background:var(--card);border:1px solid var(--border);border-radius:14px;padding:10px 12px}
    summary{cursor:pointer;font-weight:600;list-style:none}
    summary::-webkit-details-marker{display:none}
    .steps{margin:8px 0 0 0;padding-left:18px}
    .step{margin:2px 0}
    .pill{display:inline-block;margin-right:6px}
    .row-failed{box-shadow:inset 3px 0 0 var(--fail-fg)}
    .row-aborted{box-shadow:inset 3px 0 0 var(--ab-fg)}
    .row-passed{box-shadow:inset 3px 0 0 var(--ok-fg)}
    .top-link{display:inline-block;margin-top:8px;color:var(--accent);text-decoration:none}
  </style>
</head>
<body>
""");
        sb.append("<h1>Integration Tests Report</h1>\n");
        sb.append("<div class='meta'>Generated at ")
          .append(TS_FMT.format(Instant.now()))
          .append(" • Total: ").append(ORDER.size())
          .append(" • Passed: ").append(counts.getOrDefault(Status.PASSED, 0L))
          .append(" • Failed: ").append(counts.getOrDefault(Status.FAILED, 0L))
          .append(" • Aborted: ").append(counts.getOrDefault(Status.ABORTED, 0L))
          .append("</div>\n");

        sb.append("<table>\n<thead><tr>")
          .append("<th>#</th><th>Test</th><th>Description</th><th>Status</th><th>Duration</th>")
          .append("</tr></thead>\n<tbody>\n");

        int i = 0;
        for (String id : ORDER) {
            TestEntry e = TESTS.get(id);
            if (e == null) continue;
            i++;
            String anchor = "t" + i;
            String badgeClass = switch (e.status) {
                case PASSED -> "ok";
                case FAILED -> "fail";
                case ABORTED -> "aborted";
                default -> "aborted";
            };
            String dur = e.end != null && e.start != null
                    ? human(Duration.between(e.start, e.end))
                    : "-";
            sb.append("<tr>")
              .append("<td class='mono'>").append(i).append("</td>")
              .append("<td><a class='mono' href='#").append(anchor).append("'>")
              .append(escape(e.className)).append(".").append(escape(e.methodName))
              .append("</a><div class='muted'>").append(escape(e.displayName)).append("</div></td>")
              .append("<td>").append(escape(e.description)).append("</td>")
              .append("<td><span class='badge ").append(badgeClass).append("'>")
              .append(e.status).append("</span></td>")
              .append("<td>").append(dur).append("</td>")
              .append("</tr>\n");
        }
        sb.append("</tbody></table>\n");

        // Details per test
        sb.append("<h2 style='margin-top:28px'>Details</h2>\n");
        i = 0;
        for (String id : ORDER) {
            TestEntry e = TESTS.get(id);
            if (e == null) continue;
            i++;
            String anchor = "t" + i;
            sb.append("<a id='").append(anchor).append("'></a>\n");
            sb.append("<details open>\n<summary>")
              .append("<span class='mono'>").append(escape(e.className)).append(".").append(escape(e.methodName)).append("</span>")
              .append(" — ").append(escape(e.displayName)).append("</summary>\n");
            sb.append("<div class='muted'>Start: ").append(e.start != null ? TS_FMT.format(e.start) : "-")
              .append(" • End: ").append(e.end != null ? TS_FMT.format(e.end) : "-")
              .append("</div>\n");
            if (e.description != null && !e.description.isBlank()) {
                sb.append("<div><b>Description:</b> ").append(escape(e.description)).append("</div>\n");
            }
            if (e.error != null) {
                sb.append("<div style='color:#b01e1e'><b>Error:</b> ").append(escape(e.error)).append("</div>\n");
            }
            sb.append("<div><b>Steps:</b>\n<ul class='steps'>\n");
            if (e.steps == null || e.steps.isEmpty()) {
                sb.append("<li class='step muted'>(no steps recorded)</li>\n");
            } else {
                for (String s : e.steps) {
                    sb.append("<li class='step'>").append(escape(s)).append("</li>\n");
                }
            }
            sb.append("</ul></div>\n</details>\n<hr/>\n");
        }

        sb.append("</body></html>");
        return sb.toString();
    }
    private Map<Status, Long> summarize() {
        Map<Status, Long> out = new LinkedHashMap<>();
        for (String id : ORDER) {
            TestEntry e = TESTS.get(id);
            if (e == null) continue;
            out.merge(e.status == null ? Status.ABORTED : e.status, 1L, Long::sum);
        }
        return out;
    }

    private static String human(Duration d) {
        long ms = d.toMillis();
        if (ms < 1000) return ms + " ms";
        long s = ms / 1000;
        long rem = ms % 1000;
        return s + "." + String.format("%03d", rem) + " s";
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");
    }

    private static class TestEntry {
        String uniqueId;
        String displayName;
        String className;
        String methodName;
        String description;
        Instant start;
        Instant end;
        Status status = Status.ABORTED;
        List<String> steps = new ArrayList<>();
        String error;
    }
}
