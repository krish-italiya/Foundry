import { useState, type FormEvent } from "react";
import type { CreateDeploymentInput, Deployment, DeploymentStatus, Project } from "../types/workspace";
import DeploymentList from "../components/DeploymentList";

interface ProjectDeploymentsTabProps {
  project: Project;
  deployments: Deployment[];
  loadingDeployments: boolean;
  creatingDeployment: boolean;
  selectedDeploymentId: string;
  onCreateDeployment: (input: CreateDeploymentInput) => Promise<Deployment | null>;
  onOpenDeployment: (deployment: Deployment) => void;
}

const statusOptions: { value: "ALL" | DeploymentStatus; label: string }[] = [
  { value: "ALL", label: "All statuses" },
  { value: "QUEUED", label: "Queued" },
  { value: "BUILDING", label: "Building" },
  { value: "STARTING", label: "Starting" },
  { value: "HEALTH_CHECK", label: "Health check" },
  { value: "SUCCEEDED", label: "Succeeded" },
  { value: "FAILED", label: "Failed" }
];

export default function ProjectDeploymentsTab({
  project, deployments, loadingDeployments, creatingDeployment, selectedDeploymentId, onCreateDeployment, onOpenDeployment
}: ProjectDeploymentsTabProps) {
  const [sourceRef, setSourceRef] = useState("main");
  const [servicePath, setServicePath] = useState("");
  const [query, setQuery] = useState("");
  const [status, setStatus] = useState<"ALL" | DeploymentStatus>("ALL");
  const [page, setPage] = useState(1);
  const pageSize = 8;

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const created = await onCreateDeployment({ sourceRef: sourceRef.trim(), servicePath: servicePath.trim() || null });
    if (created) {
      setSourceRef("main");
      setServicePath("");
      onOpenDeployment(created);
    }
  }

  const filtered = deployments.filter((deployment) => {
    const normalized = query.trim().toLowerCase();
    const matchesText = !normalized
      || deployment.id.toLowerCase().includes(normalized)
      || deployment.sourceRef.toLowerCase().includes(normalized)
      || (deployment.servicePath ?? "").toLowerCase().includes(normalized)
      || deployment.status.toLowerCase().includes(normalized);
    return matchesText && (status === "ALL" || deployment.status === status);
  });
  const pageCount = Math.max(1, Math.ceil(filtered.length / pageSize));
  const currentPage = Math.min(page, pageCount);
  const visible = filtered.slice((currentPage - 1) * pageSize, currentPage * pageSize);

  function changeQuery(value: string) {
    setQuery(value);
    setPage(1);
  }
  function changeStatus(value: "ALL" | DeploymentStatus) {
    setStatus(value);
    setPage(1);
  }

  return (
    <>
      {!project.buildTrusted && <div className="trust-callout"><span className="queue-note-icon">!</span><div><strong>Build trust is off for {project.name}</strong><p>Foundry needs project-level trust or an administrator allowlist entry before it can run repository scripts. Review the source and manage trust in Project Overview.</p></div></div>}
      <form className="surface-card deployment-create-card" onSubmit={(event) => void submit(event)}>
        <div className="card-heading"><div><div className="section-kicker">NEW RELEASE</div><h2>Deploy {project.name}</h2><p>Build the selected branch or tag into a runnable application.</p></div><span className="deploy-form-icon">↗</span></div>
        <div className="deployment-form-grid">
          <label className="field-label">Source ref<input value={sourceRef} onChange={(event) => setSourceRef(event.target.value)} placeholder="main" maxLength={255} required /></label>
          <label className="field-label">Service path <span className="field-hint">Optional · for monorepos</span><input value={servicePath} onChange={(event) => setServicePath(event.target.value)} placeholder="Leave blank to auto-detect" maxLength={512} /></label>
          <button className="button button-primary deploy-button" type="submit" disabled={creatingDeployment}>{creatingDeployment ? "Queueing…" : "Deploy now"} <span>→</span></button>
        </div>
        <div className="form-helper"><span className="mini-live-dot" /> Build, image packaging, startup, and health-check progress appears in Build &amp; Runtime.</div>
      </form>

      <section className="surface-card deployment-history-card">
        <div className="card-heading"><div><div className="section-kicker">RELEASE ACTIVITY</div><h2>Deployment history <span className="heading-count">{deployments.length}</span></h2><p>History for {project.name} only.</p></div></div>
        <div className="history-toolbar">
          <label className="search-field"><span>⌕</span><input value={query} onChange={(event) => changeQuery(event.target.value)} placeholder="Search release ID, ref, path…" aria-label="Search deployments" /></label>
          <label className="filter-select"><span>STATUS</span><select value={status} onChange={(event) => changeStatus(event.target.value as "ALL" | DeploymentStatus)}>{statusOptions.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}</select></label>
        </div>
        <DeploymentList deployments={visible} selectedDeploymentId={selectedDeploymentId} loading={loadingDeployments} emptyTitle="No matching releases" emptyDescription="Change the search or status filter, or start a new deployment." onSelect={onOpenDeployment} />
        {filtered.length > pageSize && <div className="pagination-bar"><span>Showing {(currentPage - 1) * pageSize + 1}–{Math.min(currentPage * pageSize, filtered.length)} of {filtered.length} releases</span><div className="pagination-actions"><button className="button button-quiet" onClick={() => setPage((current) => Math.max(1, current - 1))} disabled={currentPage === 1} type="button">← Previous</button><span className="page-indicator">{currentPage} / {pageCount}</span><button className="button button-quiet" onClick={() => setPage((current) => Math.min(pageCount, current + 1))} disabled={currentPage === pageCount} type="button">Next →</button></div></div>}
        <div className="card-footer"><span className="mini-live-dot" /> Auto-refresh every 5 seconds <span className="bottom-spacer" /><span className="muted-small">{filtered.length} of {deployments.length} shown</span></div>
      </section>
    </>
  );
}