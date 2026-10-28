# Foundry Session Handoff

Last updated: 2026-10-28 (Asia/Kolkata)

This document is the durable restart point for a new ChatGPT conversation. Read this before asking the user to repeat project context.

## Project and working branch

- Repository: https://github.com/krish-italiya/Foundry
- Local Windows path: \`C:\Users\LENOVO\OneDrive\Desktop\Dev\Foundry\Foundry\`
- Active branch: \`feat/docker-runtime-adapter\`
- Draft pull request: https://github.com/krish-italiya/Foundry/pull/13
- Related acceptance-criteria issue: https://github.com/krish-italiya/Foundry/issues/12
- PR #13 has **not** been merged. Fetch/pull the latest branch before making changes; the branch head may advance after this note.

## Product goal

Foundry is a Heroku-like platform prototype: register a repository, select a branch/ref, build a supported app, package it into a runtime image, start it, check health, and provide a local preview URL.

Keep work project-driven and explain concepts in simple language when they unlock the next practical step. Avoid starting a different project before Foundry is complete. Prefer single-line PowerShell commands when practical and do not claim tests passed unless the user supplies output or a CI result verifies them.

## Current implementation

### Docker deployment

- Docker Desktop is installed on Windows; both Docker Client and Server reported versions, and \`docker run --rm hello-world\` passed.
- Runtime mode is opt-in via environment variables; committed defaults keep Docker/local runtime disabled.
- A trusted \`render-examples/express-hello-world\` deployment succeeded through Foundry: repository checkout, build, Docker image, container startup with loopback-only port publishing and resource limits, streaming logs, HTTP health check, and local URL.
- The first Node container image build hit \`EACCES: permission denied, mkdir '/app/node_modules'\`. The generated Dockerfile now runs \`RUN chown node:node /app\` before switching to \`USER node\`, and a regression assertion covers the instruction order. The subsequent deployment succeeded.
- A separate \`ionic-team/ionic-react-conference-app\` React/Vite frontend also built and served via Foundry's static Nginx Docker runtime.
- Supported packaging modes are limited (Node start, static Vite output, supported Java JARs). Do not claim universal GitHub repository compatibility.

### Frontend product structure and UX

Top-level navigation: **Overview** and **Projects**. Selecting a project opens its own workspace with tabs: **Overview**, **Deployments**, **Build & Runtime**. Release history, filters/pagination, logs, release details and the application URL are project-scoped. Overview is informational and should not link directly into a release's runtime page.

The frontend was refactored into:
- \`src/types/workspace.ts\`: typed domain/API models.
- \`src/services/api.ts\`: shared HTTP client/error handling.
- \`src/utils/format.ts\`: formatting helpers.
- \`src/hooks/useFoundryWorkspace.ts\`: API-backed workspace state and mutations.
- \`src/components/\`: shared shell, banners, empty state, forms, lists, badge, pipeline, terminal logs.
- \`src/pages/\`: Overview, Projects, ProjectWorkspace and its project tabs.
- \`src/styles/base.css\`, \`workspace.css\`, \`project-workspace.css\`, composed by \`src/styles.css\`.
- Root \`src/App.tsx\` coordinates top-level navigation and connects state to page components.

The user reported that project navigation, project-level tabs, deployment search/status filters/pagination, selecting releases/logs, and switching projects work as expected.

## Verification recorded so far

- Full backend Maven suite: **59 tests, 0 failures, 0 errors, 0 skipped** (user supplied terminal output on 2026-10-28).
- Frontend \`npm run build\`: passed at UI code commit \`118d6fc9732553645d6ee69fbf4882bc0ada4965\`; \`tsc --noEmit\` and Vite production build succeeded (48 modules transformed).
- No backend source changed after the 59-test run. Later commits included the final UI navigation simplification and documentation updates.
- Manual UI smoke test of the new project-scoped workflow: user confirmed it works as expected.
- Real Docker deployment tests succeeded for the Express service and the Vite static frontend.
- Current feature branch contains the lifecycle recovery implementation and new tests (Docker orphan cleanup/retries, interrupted-deployment recovery, worker startup gating). The latest changes have not yet been tested locally; run the full Maven suite and frontend build before doing live Docker shutdown/restart checks. Keep PR #13 in draft until then.

## Immediate next steps

1. Pull the latest `origin/feat/docker-runtime-adapter` before any further edits.
2. Run the full backend Maven suite and frontend production build after the lifecycle recovery changes; 70 tests is the expected backend total if all new tests are discovered, not a pre-confirmed result.
3. With Docker Desktop running, validate graceful API shutdown and abrupt restart: existing containers/images should be removed; previously successful rows should become `INTERRUPTED`; stranded `BUILDING`/`STARTING`/`HEALTH_CHECK` rows should become `FAILED`; queued work should wait until recovery completes.
4. Exercise cleanup when Docker Desktop is temporarily unavailable and confirm the scheduled retry removes orphaned containers/images once Docker returns.
5. Update PR #13 status truthfully and only mark it ready for review when automated and live checks pass.

## Important safety boundary

Repository build scripts currently execute on the Windows host before Docker packaging. Runtime containers have resource limits and non-root execution, but this is not a complete hostile-code sandbox. Do not expose Foundry publicly or execute arbitrary repositories. Public hosting requires an isolated build boundary, credential isolation, authentication/authorization, quotas, and public DNS/TLS ingress.

## Restarting the local development environment

From the repository root, API PowerShell session:

\`\`\`powershell
$env:FOUNDRY_WORKER_ENABLED='true'; $env:FOUNDRY_BUILD_ENABLED='true'; $env:FOUNDRY_RUNTIME_DOCKER_ENABLED='true'; $env:FOUNDRY_RUNTIME_LOCAL_ENABLED='false'; $env:FOUNDRY_RUNTIME_LOCAL_SAMPLE_MODE_ENABLED='false'; Push-Location .\\apps\\api\\foundry-api; .\\mvnw.cmd spring-boot:run
\`\`\`

Second PowerShell session, from repository root:

\`\`\`powershell
Push-Location .\\apps\\web; npm run dev
\`\`\`

Dashboard: http://127.0.0.1:5173. Docker-mode variables are session-scoped; set them again if the API process is restarted in a new terminal.
