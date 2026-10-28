import { useState, type FormEvent } from "react";
import type { CreateProjectInput, Project } from "../types/workspace";

interface ProjectFormProps {
  loading: boolean;
  onCreate: (input: CreateProjectInput) => Promise<Project | null>;
  onCancel: () => void;
}

export default function ProjectForm({ loading, onCreate, onCancel }: ProjectFormProps) {
  const [name, setName] = useState("");
  const [repositoryUrl, setRepositoryUrl] = useState("");
  const [buildTrusted, setBuildTrusted] = useState(false);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const created = await onCreate({
      name: name.trim(),
      repositoryUrl: repositoryUrl.trim(),
      buildTrusted
    });
    if (created) {
      setName("");
      setRepositoryUrl("");
      setBuildTrusted(false);
    }
  }

  return (
    <form className="surface-card project-form-card" onSubmit={(event) => void submit(event)}>
      <div className="form-title">Register a repository</div>
      <p className="form-description">Give the repository a recognizable name. Trust is optional and can be enabled later.</p>
      <div className="form-grid">
        <label>Project name<input value={name} onChange={(event) => setName(event.target.value)} placeholder="e.g. storefront-api" maxLength={80} required /></label>
        <label>Repository URL<input type="url" value={repositoryUrl} onChange={(event) => setRepositoryUrl(event.target.value)} placeholder="https://github.com/org/repository" maxLength={2048} required /></label>
      </div>
      <label className="trust-consent">
        <input className="trust-consent-input" type="checkbox" checked={buildTrusted} onChange={(event) => setBuildTrusted(event.target.checked)} />
        <span className="trust-consent-copy"><strong>Trust this repository for local builds</strong><small>Foundry may execute dependency-installation, build, test, and start scripts on this computer. Enable only for code you trust.</small></span>
      </label>
      <div className="form-actions">
        <button type="button" className="button button-quiet" onClick={onCancel}>Cancel</button>
        <button className="button button-primary" type="submit" disabled={loading}>{loading ? "Registering…" : "Register project"}</button>
      </div>
    </form>
  );
}