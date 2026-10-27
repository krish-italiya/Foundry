import { useState } from "react";
import type { CreateProjectInput, Project } from "../types/workspace";
import ProjectForm from "../components/ProjectForm";
import ProjectList from "../components/ProjectList";

interface ProjectsPageProps {
  projects: Project[];
  selectedProjectId: string;
  loadingProjects: boolean;
  creatingProject: boolean;
  onCreateProject: (input: CreateProjectInput) => Promise<Project | null>;
  onOpenProject: (project: Project) => void;
  onRefresh: () => void;
}

export default function ProjectsPage({
  projects, selectedProjectId, loadingProjects, creatingProject, onCreateProject, onOpenProject, onRefresh
}: ProjectsPageProps) {
  const [formOpen, setFormOpen] = useState(false);
  return (
    <>
      <section className="page-heading-row">
        <div><div className="eyebrow"><span className="eyebrow-line" />WORKSPACE / PROJECTS</div><h1>Projects</h1><p className="page-subtitle">Connect repositories and open a project workspace to manage releases and runtime activity.</p></div>
        <button className="button button-primary" onClick={() => setFormOpen((open) => !open)}>{formOpen ? "Close form" : "＋ New project"}</button>
      </section>
      {formOpen && <ProjectForm loading={creatingProject} onCreate={async (input) => {
        const created = await onCreateProject(input);
        if (created) setFormOpen(false);
        return created;
      }} onCancel={() => setFormOpen(false)} />}
      <section className="projects-layout projects-page-layout">
        <article className="surface-card">
          <div className="card-heading"><div><div className="section-kicker">YOUR WORKSPACE</div><h2>Repositories <span className="heading-count">{projects.length}</span></h2><p>Select a project to open its workspace.</p></div><button className="text-action" onClick={onRefresh}>Refresh ↻</button></div>
          <ProjectList projects={projects} loading={loadingProjects} selectedProjectId={selectedProjectId} onOpenProject={onOpenProject} />
          <div className="card-footer"><span className="mini-live-dot" /> Synced with Foundry API <span className="bottom-spacer" /><span className="muted-small">{projects.length} registered</span></div>
        </article>
        <article className="surface-card project-start-card">
          <div className="section-kicker">PROJECT WORKSPACE</div><h2>One project, one place</h2>
          <p>Each project groups its own deployment history, build logs, runtime status, application URL, and repository trust settings.</p>
          <div className="workspace-preview-list">
            <div><span className="workspace-preview-icon">⌂</span><span><strong>Project overview</strong><small>Repository details, trust, recent activity</small></span></div>
            <div><span className="workspace-preview-icon">↗</span><span><strong>Deployments</strong><small>Create releases and find a particular run</small></span></div>
            <div><span className="workspace-preview-icon">⌁</span><span><strong>Build &amp; Runtime</strong><small>Inspect stages, logs, health, and URL</small></span></div>
          </div>
          {projects.length === 0 && <button className="button button-primary full-width" onClick={() => setFormOpen(true)}>＋ Register your first repository</button>}
        </article>
      </section>
    </>
  );
}