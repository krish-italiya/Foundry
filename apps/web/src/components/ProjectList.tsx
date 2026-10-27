import type { Project } from "../types/workspace";
import { repositoryLabel, timeLabel } from "../utils/format";

interface ProjectListProps {
  projects: Project[];
  loading: boolean;
  selectedProjectId: string;
  onOpenProject: (project: Project) => void;
}

export default function ProjectList({ projects, loading, selectedProjectId, onOpenProject }: ProjectListProps) {
  if (loading && projects.length === 0) {
    return <div className="empty-state"><div className="loader" /><strong>Loading projects</strong><span>Connecting to the Foundry API…</span></div>;
  }
  if (projects.length === 0) {
    return <div className="empty-state"><div className="empty-illustration"><span>◇</span><i /><b /></div><strong>No projects yet</strong><span>Connect a repository to begin.</span></div>;
  }
  return (
    <div className="project-list">
      {projects.map((project, index) => (
        <button
          key={project.id}
          className={"project-row " + (project.id === selectedProjectId ? "project-row-selected" : "")}
          onClick={() => onOpenProject(project)}
        >
          <span className={"repo-avatar repo-avatar-" + (index % 4)}>{project.name.slice(0, 1).toUpperCase()}</span>
          <span className="project-row-main">
            <strong>{project.name}</strong>
            <span title={project.repositoryUrl}>{repositoryLabel(project.repositoryUrl)}</span>
            <span className={"project-trust-label " + (project.buildTrusted ? "project-trust-enabled" : "project-trust-disabled")}>{project.buildTrusted ? "Build trust enabled" : "Build trust off"}</span>
          </span>
          <span className="project-row-meta">{timeLabel(project.createdAt).split(",")[0]}</span>
          <span className="row-chevron">›</span>
        </button>
      ))}
    </div>
  );
}