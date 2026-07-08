# Claude CI/CD Pipelines

Two separate, reusable pipelines, each for GitHub Actions and Jenkins.

1. **Review**: read-only. Claude reads the diff and codebase and leaves
   inline and summary comments. It has no tools that can change anything.
2. **Autofix on failure**: runs after a build or test command fails. Claude
   does not edit anything directly. It reads the failure output and the
   codebase, then returns a structured fix plan (diagnosis, confidence,
   and an ordered list of line-anchored find/replace edits) that a small
   script applies deterministically. The pipeline reruns the failing
   command itself to verify the result independently. A new PR is opened
   only if that verification passes and Claude's confidence met the bar.
   Nothing is ever pushed to the branch that failed.

## GitHub Actions

Files:
- `.github/workflows/claude-review.yml`: reusable review workflow.
- `.github/workflows/claude-review-caller-example.yml`: how a repo calls it.
- `.github/workflows/claude-autofix-on-failure.yml`: reusable autofix workflow.
- `.github/workflows/claude-autofix-on-failure-caller-example.yml`: how a
  repo calls it, wired up as a follow-up job after a test job.
- `resources/schemas/claude-fix-schema.json`: the schema Claude's fix output
  must conform to.
- `resources/scripts/apply-fixes.py`: applies a fix plan atomically.

Setup:
1. Host both `claude-review.yml` and `claude-autofix-on-failure.yml` in a
   central repo (this one).
2. In each repo, add caller workflows following the examples.
3. Add `ANTHROPIC_API_KEY` as a repository or org secret.

Notes:
- `claude-review.yml` requests `contents: read`, not `write`. There is no
  tool access that would let it change files even if permissions were
  broader, but keeping the token scope minimal is good practice regardless.
- The review pipeline computes the diff against the PR's base branch itself
  (`git diff origin/<base>...HEAD`) and hands Claude that file directly,
  rather than letting it discover the change on its own. By default it also
  only gets the `Read` tool, not `Grep` or `Glob`, so it cannot go searching
  the rest of the repo. Set `enable_deep_context: true` if you want it to
  trace cross-file impact (catches more, costs more).
- `max_diff_lines` (default 1500) skips the review entirely on very large
  diffs, since cost scales with diff size regardless of how tightly scoped
  the prompt is. Lockfile bumps, generated code, and vendored dependency
  updates are the usual culprits.
- `claude-autofix-on-failure.yml` requests `contents: write` and
  `pull-requests: write`, since it needs to push a new branch and open a PR.
  It only does either after independently rerunning `test_command` and
  confirming it passes.
- The autofix workflow is meant to be called with `needs: <your test job>`
  and `if: failure()`, so it only runs when that job actually fails.
- Claude never gets Edit, Write, or Bash in the autofix workflow either.
  It reads `failure-log.txt` and the codebase, then must return JSON
  matching `resources/schemas/claude-fix-schema.json` (passed via
  `--json-schema`). A separate step (`apply-fixes.py`, stdlib only, no
  pip install needed) applies that plan: it matches each fix's `original`
  text near the given line number, and only writes any file to disk if
  every fix in the plan matched cleanly. One bad match aborts the whole
  apply, untouched.
- `min_confidence` (default `medium`) gates whether a fix plan gets applied
  at all. If Claude reports `low` confidence, or returns no structured
  output, the pipeline stops there: no PR, no changes.
- `setup_command` (default `""`, optional) runs once, right after the
  workflow installs the Claude Code CLI and before `test_command` is run
  for the first time. Use it for anything `test_command` needs that a bare
  checkout doesn't already provide: system packages, a language toolchain,
  installing project dependencies. It's a single shell string, so chain
  steps with `&&`. Leave it unset if `test_command` runs on a bare checkout
  with nothing else installed. Runs before both the failure-reproduction
  step and the post-fix verification step, since GitHub Actions carries
  `$GITHUB_PATH`/env changes made in one step forward to later steps in the
  same job, so one setup step covers both reruns of `test_command`.
- `--json-schema` makes Claude Code validate its response against the
  schema before returning it, but treat that as a strong nudge, not an
  ironclad guarantee, the way you would with the Claude API's `strict`
  structured outputs. That's exactly why `apply-fixes.js` does its own
  validation on top and refuses to write anything if a fix doesn't match.
- The workflow checks out this repo into `.claude-pipelines/` to get the
  schema and script onto the runner, alongside the caller's own repo.
  Pin the `ref` on that checkout step once you've tagged a stable release,
  rather than tracking `main`.

## Jenkins

Files:
- `vars/claudeReview.groovy`: read-only review step.
- `vars/claudeAutofixOnFailure.groovy`: autofix-on-failure step.
- `jenkins/example/Jenkinsfile`: both wired into one pipeline.

`vars/` and `resources/` sit at the repo root, not nested under `jenkins/`,
because Jenkins's Global Pipeline Library auto-discovery only looks for
them at the top level of the library repo. Only the example `Jenkinsfile`
is tucked under `jenkins/`, since it's sample code for consumers to copy,
not something Jenkins needs to discover.

Setup:
1. Publish this repo as a Jenkins Shared Library named `claude-pipelines`.
2. In Jenkins credentials, add:
   - Secret text `claude-api-key`: the Anthropic API key.
   - Username/password `jenkins-git-push-creds`: push access, used only by
     the autofix step.
   - Username/password `bitbucket-api-creds` or secret text
     `github-api-token`, depending on provider.
3. Call `claudeReview(...)` from a stage gated on `changeRequest()`, passing
   `targetBranch` (or rely on the `CHANGE_TARGET` env var Jenkins sets on
   multibranch PR builds), and `claudeAutofixOnFailure(...)` from a
   `post { failure { ... } }` block, as in the example.

Jenkins has no first-party Claude integration, unlike GitHub Actions, so
both steps wrap the Claude Code CLI directly
(`npm install -g @anthropic-ai/claude-code`, then `claude --bare -p`) and
talk to GitHub or Bitbucket with plain `curl` calls. Both take `provider` as
a parameter so the same step works against either.

`claudeAutofixOnFailure` pulls the schema and apply script out of the
shared library's `resources/` folder with Jenkins's built-in
`libraryResource()` step and writes them into the workspace before use.
That's the Jenkins equivalent of the `.claude-pipelines/` checkout on the
GitHub Actions side: one canonical copy of each file, shipped with the
library itself.

`apply-fixes.py` needs `python3` on the agent running the pipeline. GitHub
Actions' `ubuntu-latest` has it out of the box; Jenkins agents vary
depending on how they're provisioned, so confirm `python3` is on `PATH`
for whichever agent label runs this stage, or add a setup step if not.

## Why verify independently instead of trusting Claude's own report

Claude's diagnosis call never touches the test command and never sees
whether its own fix works. That's deliberate: it removes any temptation to
trust a "this should pass now" claim made in the same breath as the fix.
Both autofix pipelines apply the plan, then rerun the failing command
themselves, as a step with no connection to the diagnosis call. A PR only
gets opened when there is an independently confirmed, working fix behind
it. If verification fails, or a fix doesn't match the file as expected,
the pipeline stops and nothing is committed or opened.

## Guardrails worth keeping as you extend these

- **Review stays read-only.** Do not add Edit, Write, or Bash access to the
  review pipeline. If you want it to also fix things, that is a different
  job with different risk, which is exactly why autofix is separate.
- **Autofix never edits directly either.** Claude only gets Read, Grep, and
  Glob, even in the autofix pipeline. It proposes changes as data; a
  plain, auditable script applies them. If you extend the fix schema,
  keep it to a shape a script can safely apply, resist the pull to give
  Claude Edit access instead once the schema gets complicated.
- **Autofix branches, never rewrites the original.** It always works on a
  new `claude-fix/...` branch and opens a new PR. The original branch or PR
  is untouched.
- **Confidence is self-reported, not a guarantee.** Treat `min_confidence`
  as a coarse filter, not a substitute for review. A `high` confidence fix
  that passes verification is still a fix a human should read before it
  merges.
- **A human still merges.** Neither pipeline auto-merges anything. Treat
  the opened PR the same as you would a junior engineer's PR: read it
  before it merges, especially for anything client facing.

## Cost estimate

Review is a single pass over just the diff, roughly $0.10 to $0.40 per PR
on Sonnet for a typical change, since it's handed the diff directly and
capped at Read-only tool access by default. Turning on `enable_deep_context`
or reviewing very large diffs pushes that up.

Autofix is now also a single pass, not an iterate-edit-rerun loop: one
Read-only diagnosis call that reads the failure log and returns JSON.
Expect something closer to $0.20 to $0.75 per failure for a typical
codebase, well under what the previous Edit/Write agentic version would
have cost, since there's no tool-call loop and no self-verification
attempt inside the Claude session. These are estimates from token math,
not benchmarks. Measure against real runs in a target repo before quoting
a cost figure to a client.
