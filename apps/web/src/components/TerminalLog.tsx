import type { LogEntry } from "../types/workspace";

interface TerminalLogProps {
  logs: LogEntry[];
  loading: boolean;
  hasMore: boolean;
  sequence: number;
  deploymentId?: string;
}

export default function TerminalLog({ logs, loading, hasMore, sequence, deploymentId }: TerminalLogProps) {
  return (
    <section className="surface-card log-panel">
      <div className="log-panel-heading">
        <div><div className="section-kicker">OBSERVABILITY</div><h2>Build &amp; runtime output <span className="heading-count">{logs.length}</span></h2><p>Command output, image-build events, container logs, and health-check results.</p></div>
        <span className="logs-polling"><span className="mini-live-dot" /> Live polling</span>
      </div>
      <div className="logs-toolbar">
        <div className="logs-toolbar-left"><span className="terminal-glyph">⌁</span><strong>Event stream</strong><span className="logs-count">{logs.length} entries</span></div>
        <span className="logs-polling"><span className="mini-live-dot" /> Refreshes every 5s</span>
      </div>
      <div className="terminal">
        {logs.length === 0 && loading
          ? <div className="terminal-empty"><div className="loader" /><span>Loading deployment events…</span></div>
          : logs.length === 0
            ? <div className="terminal-empty"><span className="terminal-prompt">$</span>No logs yet. Events appear as the worker processes this deployment.<span className="terminal-cursor" /></div>
            : <div className="terminal-lines">
              {logs.map((entry) => (
                <div className="terminal-line" key={entry.id}>
                  <span className="log-sequence">{String(entry.sequenceNumber).padStart(3, "0")}</span>
                  <span className="log-time">{new Date(entry.timestamp).toLocaleTimeString([], { hour12: false })}</span>
                  <span className={"log-stage log-stage-" + entry.stage.toLowerCase().replace(/_/g, "-")}>{entry.stage}</span>
                  <span className={"log-level log-level-" + entry.severity.toLowerCase()}>{entry.severity}</span>
                  <span className="log-message">{entry.message}</span>
                </div>
              ))}
              {hasMore && <div className="logs-more-note">Additional older entries are available through the paginated logs API.</div>}
              <div className="terminal-tail"><span className="terminal-prompt">$</span> Listening for new events<span className="terminal-cursor" /></div>
            </div>}
      </div>
      <div className="log-footer"><span>SEQUENCE CURSOR</span><strong>{sequence.toString().padStart(3, "0")}</strong><span className="footer-divider" /><span>API SOURCE</span><code>/api/v1/deployments/{deploymentId ?? "{id}"}/logs</code><span className="bottom-spacer" />{loading && <span className="muted-small">Refreshing…</span>}</div>
    </section>
  );
}