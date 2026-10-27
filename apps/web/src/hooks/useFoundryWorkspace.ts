import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { api } from "../services/api";
import type {
  ApiState,
  CreateDeploymentInput,
  CreateProjectInput,
  Deployment,
  DeploymentLogs,
  LogEntry,
  Project
} from "../types/workspace";

export function useFoundryWorkspace() {
  const [projects, setProjects] = useState<Project[]>([]);
  const [selectedProjectId, setSelectedProjectId] = useState("");
  const [deployments, setDeployments] = useState<Deployment[]>([]);
  const [selectedDeploymentId, setSelectedDeploymentId] = useState("");
  const [logs, setLogs] = useState<LogEntry[]>([]);
  const [logsHasMore, setLogsHasMore] = useState(false);
  const [apiState, setApiState] = useState<ApiState>("checking");
  const [loadingProjects, setLoadingProjects] = useState(true);
  const [loadingDeployments, setLoadingDeployments] = useState(false);
  const [loadingLogs, setLoadingLogs] = useState(false);
  const [creatingProject, setCreatingProject] = useState(false);
  const [creatingDeployment, setCreatingDeployment] = useState(false);
  const [updatingBuildTrust, setUpdatingBuildTrust] = useState(false);
  const [pageError, setPageError] = useState("");
  const [notice, setNotice] = useState("");
  const lastLogSequence = useRef(0);

  const selectedProject = useMemo(
    () => projects.find((project) => project.id === selectedProjectId),
    [projects, selectedProjectId]
  );
  const selectedDeployment = useMemo(
    () => deployments.find((deployment) => deployment.id === selectedDeploymentId),
    [deployments, selectedDeploymentId]
  );

  const refreshProjects = useCallback(async () => {
    setPageError("");
    try {
      const items = await api<Project[]>("/api/v1/projects");
      setProjects(items);
      setApiState("online");
      setSelectedProjectId((current) =>
        current && items.some((project) => project.id === current) ? current : items[0]?.id ?? ""
      );
    } catch (error) {
      setApiState("offline");
      setPageError(error instanceof Error ? error.message : "Could not refresh projects.");
    } finally {
      setLoadingProjects(false);
    }
  }, []);

  useEffect(() => {
    let cancelled = false;
    const load = async () => {
      try {
        const [items] = await Promise.all([
          api<Project[]>("/api/v1/projects"),
          api<{ status: string }>("/actuator/health")
        ]);
        if (cancelled) return;
        setProjects(items);
        setApiState("online");
        setSelectedProjectId((current) =>
          current && items.some((project) => project.id === current) ? current : items[0]?.id ?? ""
        );
      } catch (error) {
        if (cancelled) return;
        setApiState("offline");
        setPageError(error instanceof Error ? error.message : "Could not connect to Foundry API.");
      } finally {
        if (!cancelled) setLoadingProjects(false);
      }
    };
    void load();
    const interval = window.setInterval(() => void load(), 15000);
    return () => {
      cancelled = true;
      window.clearInterval(interval);
    };
  }, []);

  useEffect(() => {
    let cancelled = false;
    if (!selectedProjectId) {
      setDeployments([]);
      setSelectedDeploymentId("");
      setLoadingDeployments(false);
      return;
    }

    const load = async () => {
      try {
        setLoadingDeployments(true);
        const items = await api<Deployment[]>(
          "/api/v1/projects/" + encodeURIComponent(selectedProjectId) + "/deployments"
        );
        if (cancelled) return;
        setDeployments(items);
        setApiState("online");
        setSelectedDeploymentId((current) =>
          current && items.some((deployment) => deployment.id === current)
            ? current
            : items[0]?.id ?? ""
        );
      } catch (error) {
        if (!cancelled) {
          setPageError(error instanceof Error ? error.message : "Could not load deployment history.");
        }
      } finally {
        if (!cancelled) setLoadingDeployments(false);
      }
    };

    void load();
    const interval = window.setInterval(() => void load(), 5000);
    return () => {
      cancelled = true;
      window.clearInterval(interval);
    };
  }, [selectedProjectId]);

  useEffect(() => {
    let cancelled = false;
    let inFlight = false;
    lastLogSequence.current = 0;
    setLogs([]);
    setLogsHasMore(false);

    if (!selectedDeploymentId) {
      setLoadingLogs(false);
      return;
    }

    const load = async () => {
      if (inFlight) return;
      inFlight = true;
      const afterSequence = lastLogSequence.current;
      try {
        setLoadingLogs(true);
        const page = await api<DeploymentLogs>(
          "/api/v1/deployments/" + encodeURIComponent(selectedDeploymentId) +
          "/logs?afterSequence=" + afterSequence + "&limit=100"
        );
        if (cancelled) return;
        setLogs((current) => afterSequence === 0 ? page.entries : [...current, ...page.entries]);
        lastLogSequence.current = page.nextAfterSequence;
        setLogsHasMore(page.hasMore);
      } catch (error) {
        if (!cancelled) {
          setPageError(error instanceof Error ? error.message : "Could not load deployment logs.");
        }
      } finally {
        inFlight = false;
        if (!cancelled) setLoadingLogs(false);
      }
    };

    void load();
    const interval = window.setInterval(() => void load(), 5000);
    return () => {
      cancelled = true;
      window.clearInterval(interval);
    };
  }, [selectedDeploymentId]);

  const createProject = useCallback(async (input: CreateProjectInput): Promise<Project | null> => {
    setPageError("");
    setNotice("");
    setCreatingProject(true);
    try {
      const created = await api<Project>("/api/v1/projects", {
        method: "POST",
        body: JSON.stringify(input)
      });
      setProjects((current) => [created, ...current.filter((project) => project.id !== created.id)]);
      setSelectedProjectId(created.id);
      setNotice(created.buildTrusted
        ? "Project registered and trusted for local builds."
        : "Project registered. Enable Build trust before running repository build scripts.");
      setApiState("online");
      return created;
    } catch (error) {
      setPageError(error instanceof Error ? error.message : "Could not register the project.");
      return null;
    } finally {
      setCreatingProject(false);
    }
  }, []);

  const toggleBuildTrust = useCallback(async (project: Project): Promise<boolean> => {
    if (updatingBuildTrust) return false;
    const buildTrusted = !project.buildTrusted;
    if (buildTrusted && !window.confirm(
      "Trust this repository for local builds? Foundry will execute repository-defined build and start scripts on this computer. Continue only if you trust this code."
    )) return false;

    setPageError("");
    setNotice("");
    setUpdatingBuildTrust(true);
    try {
      const updated = await api<Project>(
        "/api/v1/projects/" + encodeURIComponent(project.id) + "/build-trust",
        { method: "PATCH", body: JSON.stringify({ buildTrusted }) }
      );
      setProjects((current) => current.map((item) => item.id === updated.id ? updated : item));
      setNotice(buildTrusted
        ? "Build trust enabled for " + updated.name + ". You no longer need to add this project to the administrator allowlist."
        : "Build trust revoked for " + updated.name + ".");
      return true;
    } catch (error) {
      setPageError(error instanceof Error ? error.message : "Could not update repository build trust.");
      return false;
    } finally {
      setUpdatingBuildTrust(false);
    }
  }, [updatingBuildTrust]);

  const createDeployment = useCallback(async (
    project: Project,
    input: CreateDeploymentInput
  ): Promise<Deployment | null> => {
    setPageError("");
    setNotice("");
    setCreatingDeployment(true);
    try {
      const created = await api<Deployment>(
        "/api/v1/projects/" + encodeURIComponent(project.id) + "/deployments",
        { method: "POST", body: JSON.stringify(input) }
      );
      setDeployments((current) => [created, ...current.filter((deployment) => deployment.id !== created.id)]);
      setSelectedDeploymentId(created.id);
      setNotice("Deployment request accepted. Follow checkout, build, startup, and health-check progress in this project's workspace.");
      setApiState("online");
      return created;
    } catch (error) {
      setPageError(error instanceof Error ? error.message : "Could not request deployment.");
      return null;
    } finally {
      setCreatingDeployment(false);
    }
  }, []);

  const clearFeedback = useCallback(() => {
    setPageError("");
    setNotice("");
  }, []);

  return {
    projects,
    selectedProjectId,
    setSelectedProjectId,
    selectedProject,
    deployments,
    selectedDeploymentId,
    setSelectedDeploymentId,
    selectedDeployment,
    logs,
    logsHasMore,
    apiState,
    loadingProjects,
    loadingDeployments,
    loadingLogs,
    creatingProject,
    creatingDeployment,
    updatingBuildTrust,
    pageError,
    notice,
    refreshProjects,
    createProject,
    toggleBuildTrust,
    createDeployment,
    clearFeedback,
    lastLogSequence: lastLogSequence.current
  };
}