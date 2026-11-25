const REQUIRED_JOBS = [
  'test (ubuntu-latest)',
  'test (macos-latest)',
  'test (windows-latest)',
  'Build',
];

module.exports = async function checkReleaseCI({ github, context, core, commit }) {
  const repo = context.repo;
  const run = await getLatestPassingBuild(github, repo, commit);
  await checkRequiredJobs(github, repo, run);

  // A new run or rerun can start while GitHub returns the job history.
  const current = await getLatestPassingBuild(github, repo, commit);
  if (current.id !== run.id || current.run_attempt !== run.run_attempt) {
    throw new Error(`Build changed during validation. Retry once CI passes for ${commit}.`);
  }
  core.info(`CI passed: ${run.html_url}/attempts/${run.run_attempt}`);
};

async function getLatestPassingBuild(github, repo, commit) {
  const runs = await github.paginate(github.rest.actions.listWorkflowRuns, {
    ...repo,
    workflow_id: 'build.yml',
    branch: 'main',
    event: 'push',
    head_sha: commit,
    per_page: 100,
  });
  if (!runs.length) {
    throw new Error(`No Build run for ${commit} on main.`);
  }
  const pending = runs.find((run) => run.status !== 'completed');
  if (pending) {
    throw new Error(`Build is still pending: ${pending.html_url}`);
  }

  // A rerun keeps its run number but gets a new start time.
  runs.sort(
    (a, b) => b.run_started_at.localeCompare(a.run_started_at) || b.run_number - a.run_number,
  );
  const latest = runs[0];
  const failed = runs.find(
    (run) => run.run_started_at === latest.run_started_at && run.conclusion !== 'success',
  );
  if (failed) {
    throw new Error(`Build has not passed: ${failed.html_url}`);
  }
  return latest;
}

async function checkRequiredJobs(github, repo, run) {
  // A partial rerun keeps earlier results for jobs it did not repeat.
  const jobs = await github.paginate(github.rest.actions.listJobsForWorkflowRun, {
    ...repo,
    run_id: run.id,
    filter: 'all',
    per_page: 100,
  });
  jobs.sort((a, b) => b.run_attempt - a.run_attempt);
  for (const name of REQUIRED_JOBS) {
    const [latest, previous] = jobs.filter((job) => job.name === name);
    const passed = latest?.status === 'completed' && latest.conclusion === 'success';
    if (
      !passed ||
      latest.run_attempt === previous?.run_attempt ||
      latest.run_attempt > run.run_attempt
    ) {
      throw new Error(`Expected one passing '${name}' job in its latest attempt: ${run.html_url}`);
    }
  }
}
