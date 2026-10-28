import type { Deployment, LogEntry } from "../types/workspace";

interface BuildPipelineProps {
  deployment: Deployment;
  logs: LogEntry[];
}

type Stage = { title: string; detail: string; complete: (logs: LogEntry[], deployment: Deployment) => boolean };

const stages: Stage[] = [
  {
    title: "Checkout & build",
    detail: "Fetch source, install dependencies, run tests, and compile the service.",
    complete: (logs, deployment) => deployment.status === "SUCCEEDED" || logs.some((entry) => /repository build succeeded|build completed\. prepared/i.test(entry.message))
  },
  {
    title: "Package image",
    detail: "Build a runtime image from the prepared artifact.",
    complete: (logs, deployment) => deployment.status === "SUCCEEDED" || logs.some((entry) => /naming to docker\.io\/library\/foundry-deployment|image built/i.test(entry.message))
  },
  {
    title: "Start application",
    detail: "Start the constrained runtime and capture container logs.",
    complete: (logs, deployment) => deployment.status === "SUCCEEDED" || logs.some((entry) => /started docker container|started the built service at/i.test(entry.message))
  },
  {
    title: "Health check",
    detail: "Verify that the application responds over HTTP.",
    complete: (logs, deployment) => deployment.status === "SUCCEEDED" || logs.some((entry) => /health check passed/i.test(entry.message))
  }
];

export default function BuildPipeline({ deployment, logs }: BuildPipelineProps) {
  const completed = stages.map((stage) => stage.complete(logs, deployment));
  const failedIndex = deployment.status === "FAILED" ? completed.findIndex((done) => !done) : -1;
  return (
    <section className="surface-card build-timeline-card">
      <div className="card-heading"><div><div className="section-kicker">PIPELINE STATUS</div><h2>Build to runtime</h2><p>A release succeeds only after the application passes its health check.</p></div><span className="logs-polling"><span className="mini-live-dot" /> Live updates</span></div>
      <div className="stage-timeline">
        {stages.map((stage, index) => {
          const done = completed[index];
          const failed = index === failedIndex;
          return (
            <div className={"stage-card " + (failed ? "stage-failed" : done ? "stage-done" : "stage-waiting")} key={stage.title}>
              <span className="stage-number">{failed ? "!" : done ? "✓" : String(index + 1).padStart(2, "0")}</span>
              <div><strong>{stage.title}</strong><p>{stage.detail}</p><small>{failed ? "Failed here" : done ? "Completed" : "Waiting"}</small></div>
            </div>
          );
        })}
      </div>
    </section>
  );
}