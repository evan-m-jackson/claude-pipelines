// vars/claudeAutofixOnFailure.groovy
//
// Run this from a post { failure { ... } } block after a build or test
// stage fails.
//
// Claude does not edit files directly. It gets the failure output and
// read-only access to the codebase, and returns a structured fix plan
// conforming to resources/schemas/claude-fix-schema.json: a diagnosis, a
// confidence level, and an ordered list of line-anchored find/replace
// edits. resources/scripts/apply-fixes.js applies that plan atomically.
// The pipeline then reruns the failing command itself to verify the fix
// independently, and opens a new PR only if that passes. It never pushes
// to the branch that failed.
//
// This is cheaper than an agentic edit loop: one Read-only diagnosis call
// instead of an iterate-edit-rerun cycle, and no Edit/Write/Bash tool
// access to pay for or to trust.
//
// Usage from a Jenkinsfile:
//
//   post {
//       failure {
//           claudeAutofixOnFailure(
//               provider: 'bitbucket',
//               repoSlug: 'my-workspace/my-repo',
//               baseBranch: env.BRANCH_NAME,
//               testCommand: 'npm test',
//               minConfidence: 'medium',
//               credentialsId: 'claude-api-key',
//               gitCredentialsId: 'jenkins-git-push-creds',
//               scmCredentialsId: 'bitbucket-api-creds'
//           )
//       }
//   }

def call(Map config) {
    def provider         = config.provider ?: 'bitbucket'
    def repoSlug          = config.repoSlug
    def baseBranch        = config.baseBranch
    def testCommand       = config.testCommand
    def model             = config.model ?: 'claude-sonnet-4-6'
    def maxTurns          = config.maxTurns ?: 10
    def minConfidence     = config.minConfidence ?: 'medium'
    def credentialsId     = config.credentialsId ?: 'claude-api-key'
    def gitCredentialsId  = config.gitCredentialsId
    def scmCredentialsId  = config.scmCredentialsId

    if (!repoSlug || !baseBranch || !testCommand || !gitCredentialsId) {
        error("claudeAutofixOnFailure requires repoSlug, baseBranch, testCommand, and gitCredentialsId")
    }

    def fixBranch = "claude-fix/${baseBranch}-${env.BUILD_NUMBER}"

    writeFile file: 'claude-fix-schema.json', text: libraryResource('schemas/claude-fix-schema.json')
    writeFile file: 'apply-fixes.py', text: libraryResource('scripts/apply-fixes.py')

    sh """
        git config user.name "claude-bot"
        git config user.email "claude-bot@ci.local"
        git checkout -b ${fixBranch}
    """

    sh 'npm install -g @anthropic-ai/claude-code'

    sh """
        set +e
        ${testCommand} > failure-log.txt 2>&1
        echo "exit code: \$?" >> failure-log.txt
        exit 0
    """

    def prompt = """The command \`${testCommand}\` failed on this branch.
Its output is in failure-log.txt.

Read failure-log.txt and whatever source files you need to understand the
failure. Do not attempt to run the command yourself or edit any files, you
have no tools for either.

Find the root cause, not just a way to silence the error. Do not propose
deleting or skipping a failing test as a fix.

If you cannot identify a fix you're reasonably confident in, report that
honestly with low confidence and an empty fixes list, rather than
guessing.
"""
    writeFile file: 'claude-prompt.txt', text: prompt

    def proceed = false
    withCredentials([string(credentialsId: credentialsId, variable: 'ANTHROPIC_API_KEY')]) {
        sh """
            claude --bare -p "\$(cat claude-prompt.txt)" \\
                --model ${model} \\
                --max-turns ${maxTurns} \\
                --allowedTools "Read,Grep,Glob" \\
                --permission-mode bypassPermissions \\
                --output-format json \\
                --json-schema claude-fix-schema.json \\
                > claude-output.json
        """
    }

    sh "jq '.structured_output' claude-output.json > fix-plan.json"
    def structuredOutput = readFile('fix-plan.json').trim()

    if (structuredOutput == 'null') {
        echo 'Claude did not return a structured fix plan. No PR opened.'
        return
    }

    def confidence = sh(script: "jq -r '.confidence' fix-plan.json", returnStdout: true).trim()
    def rank = [low: 0, medium: 1, high: 2]
    echo "Claude's confidence: ${confidence} (need at least ${minConfidence})"
    if ((rank[confidence] ?: -1) < (rank[minConfidence] ?: 99)) {
        echo "Confidence too low. No PR opened."
        return
    }

    def applyStatus = sh(script: 'python3 apply-fixes.py fix-plan.json', returnStatus: true)
    if (applyStatus != 0) {
        echo 'One or more fixes did not match the file as expected. Nothing was changed. No PR opened.'
        return
    }

    def verifyStatus = sh(script: testCommand, returnStatus: true)
    if (verifyStatus != 0) {
        echo 'Applied fix did not pass independent verification. No PR opened.'
        return
    }

    def changed = sh(script: 'git status --porcelain', returnStdout: true).trim()
    if (!changed) {
        echo 'Verification passed but there is nothing to commit. Skipping PR.'
        return
    }

    withCredentials([usernamePassword(credentialsId: gitCredentialsId,
                                       usernameVariable: 'GIT_USER',
                                       passwordVariable: 'GIT_PASS')]) {
        sh """
            git add -A
            git commit -m "Claude: fix ${testCommand} failure"
            remote_host=\$(git remote get-url origin | sed -E 's#https?://##')
            git push "https://\${GIT_USER}:\${GIT_PASS}@\${remote_host}" ${fixBranch}
        """
    }

    openPullRequest(provider, repoSlug, fixBranch, baseBranch, testCommand, confidence, scmCredentialsId)
}

private void openPullRequest(String provider, String repoSlug, String fixBranch, String baseBranch, String testCommand, String confidence, String scmCredentialsId) {
    def explanation = sh(script: "jq -r '.explanation' fix-plan.json", returnStdout: true).trim()
    def title = "Claude: fix ${testCommand} failure on ${baseBranch}"
    def body  = "Confidence: ${confidence}\n\n${explanation}\n\nVerified by independently rerunning `${testCommand}` before opening this PR. Review before merging."

    if (provider == 'bitbucket') {
        withCredentials([usernamePassword(credentialsId: scmCredentialsId ?: 'bitbucket-api-creds',
                                           usernameVariable: 'BB_USER',
                                           passwordVariable: 'BB_PASS')]) {
            def payload = groovy.json.JsonOutput.toJson([
                title: title,
                description: body,
                source: [branch: [name: fixBranch]],
                destination: [branch: [name: baseBranch]]
            ])
            writeFile file: 'pr-payload.json', text: payload
            sh """
                curl -sf -u "\${BB_USER}:\${BB_PASS}" \\
                     -H "Content-Type: application/json" \\
                     -X POST \\
                     -d @pr-payload.json \\
                     "https://api.bitbucket.org/2.0/repositories/${repoSlug}/pullrequests"
            """
        }
    } else if (provider == 'github') {
        withCredentials([string(credentialsId: scmCredentialsId ?: 'github-api-token', variable: 'GH_TOKEN')]) {
            def payload = groovy.json.JsonOutput.toJson([
                title: title,
                body: body,
                head: fixBranch,
                base: baseBranch
            ])
            writeFile file: 'pr-payload.json', text: payload
            sh """
                curl -sf -H "Authorization: token \${GH_TOKEN}" \\
                     -H "Content-Type: application/json" \\
                     -X POST \\
                     -d @pr-payload.json \\
                     "https://api.github.com/repos/${repoSlug}/pulls"
            """
        }
    } else {
        error("Unknown provider: ${provider}. Use 'bitbucket' or 'github'.")
    }
}
