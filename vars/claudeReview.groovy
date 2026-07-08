// vars/claudeReview.groovy
//
// Read-only Claude PR review. No Edit, Write, or Bash(git commit/push)
// access, so it cannot change anything. Posts inline and summary comments
// to GitHub or Bitbucket.
//
// Usage from a Jenkinsfile:
//
//   claudeReview(
//       provider: 'bitbucket',
//       repoSlug: 'my-workspace/my-repo',
//       prId: env.CHANGE_ID,
//       credentialsId: 'claude-api-key',
//       scmCredentialsId: 'bitbucket-api-creds'
//   )

def call(Map config) {
    def provider          = config.provider ?: 'bitbucket'
    def repoSlug           = config.repoSlug
    def prId               = config.prId
    def targetBranch       = config.targetBranch ?: env.CHANGE_TARGET
    def model              = config.model ?: 'claude-sonnet-4-6'
    def maxTurns           = config.maxTurns ?: 8
    def maxDiffLines       = config.maxDiffLines ?: 1500
    def enableDeepContext  = config.enableDeepContext ?: false
    def credentialsId      = config.credentialsId ?: 'claude-api-key'
    def scmCredentialsId   = config.scmCredentialsId

    if (!repoSlug || !prId || !targetBranch) {
        error("claudeReview requires repoSlug, prId, and targetBranch (or a CHANGE_TARGET env var from a multibranch PR build)")
    }

    sh "git fetch origin ${targetBranch}"
    def diffLineCount = sh(
        script: "git diff origin/${targetBranch}...HEAD > diff.patch && wc -l < diff.patch",
        returnStdout: true
    ).trim().toInteger()

    if (diffLineCount > maxDiffLines) {
        echo "Diff has ${diffLineCount} lines, over the ${maxDiffLines} limit. Skipping automated review to avoid runaway cost. This PR needs a manual review instead."
        return
    }

    withCredentials([string(credentialsId: credentialsId, variable: 'ANTHROPIC_API_KEY')]) {
        sh 'npm install -g @anthropic-ai/claude-code'

        def prompt = """Review the pull request diff in diff.patch (already
computed, read that file first). You are read-only: you have no tools that
can edit files, so do not attempt to.

Scope: review only the lines changed in diff.patch. Do not go exploring
the rest of the codebase looking for unrelated issues. The one exception
is checking how a changed function, type, or variable is used elsewhere,
when that context is necessary to tell whether the change is correct.

1. Identify bugs, security issues, logic errors, and convention violations
   within that scope.
2. Write your findings to review-notes.md, one item per issue, each with
   the file, line, and a short explanation.
3. Write a one paragraph overall summary to review-summary.md, including
   a risk level (low, medium, high) and what a human reviewer should focus
   on.
4. If you find nothing worth flagging, say so plainly in the summary.
"""
        writeFile file: 'claude-prompt.txt', text: prompt

        def allowedTools = enableDeepContext ? 'Read,Grep,Glob' : 'Read'
        sh """
            claude --bare -p "\$(cat claude-prompt.txt)" \\
                --model ${model} \\
                --max-turns ${maxTurns} \\
                --allowedTools "${allowedTools}" \\
                --permission-mode bypassPermissions \\
                --output-format json > claude-output.json
        """

        postReviewComment(provider, repoSlug, prId, scmCredentialsId)
    }
}

private void postReviewComment(String provider, String repoSlug, String prId, String scmCredentialsId) {
    def summary = fileExists('review-summary.md') ? readFile('review-summary.md') : 'Claude review completed. No summary produced.'
    def notes   = fileExists('review-notes.md') ? readFile('review-notes.md') : ''
    def body    = notes ? "${summary}\n\n---\n\n${notes}" : summary

    if (provider == 'bitbucket') {
        withCredentials([usernamePassword(credentialsId: scmCredentialsId ?: 'bitbucket-api-creds',
                                           usernameVariable: 'BB_USER',
                                           passwordVariable: 'BB_PASS')]) {
            writeFile file: 'comment-payload.json', text: groovy.json.JsonOutput.toJson([content: [raw: body]])
            sh """
                curl -sf -u "\${BB_USER}:\${BB_PASS}" \\
                     -H "Content-Type: application/json" \\
                     -X POST \\
                     -d @comment-payload.json \\
                     "https://api.bitbucket.org/2.0/repositories/${repoSlug}/pullrequests/${prId}/comments"
            """
        }
    } else if (provider == 'github') {
        withCredentials([string(credentialsId: scmCredentialsId ?: 'github-api-token', variable: 'GH_TOKEN')]) {
            writeFile file: 'comment-payload.json', text: groovy.json.JsonOutput.toJson([body: body])
            sh """
                curl -sf -H "Authorization: token \${GH_TOKEN}" \\
                     -H "Content-Type: application/json" \\
                     -X POST \\
                     -d @comment-payload.json \\
                     "https://api.github.com/repos/${repoSlug}/issues/${prId}/comments"
            """
        }
    } else {
        error("Unknown provider: ${provider}. Use 'bitbucket' or 'github'.")
    }
}
