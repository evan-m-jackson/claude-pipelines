// vars/claudeReview.groovy
//
// Read-only Claude PR review. No Edit, Write, or Bash(git commit/push)
// access, so it cannot change anything. Claude returns a structured
// review conforming to resources/schemas/claude-review-schema.json: a
// summary, an overall risk level, and file/line-anchored findings. This
// script posts the comment(s) itself from that JSON, rather than relying
// on Claude to write files or call an SCM API directly (it has no Write
// or Bash access, so it couldn't anyway).
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

    writeFile file: 'claude-review-schema.json', text: libraryResource('schemas/claude-review-schema.json')

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
2. Report each specific issue as a finding anchored to the exact file and
   1-indexed line it occurs at, with a severity and an explanation of
   what is wrong and why.
3. Give an overall summary paragraph and risk level, covering what a
   human reviewer should pay close attention to.
4. If you find nothing worth flagging, return an empty findings list and
   say so plainly in the summary, rather than inventing minor nitpicks.
"""
        writeFile file: 'claude-prompt.txt', text: prompt

        def allowedTools = enableDeepContext ? 'Read,Grep,Glob' : 'Read'
        sh """
            claude --bare -p "\$(cat claude-prompt.txt)" \\
                --model ${model} \\
                --max-turns ${maxTurns} \\
                --allowedTools "${allowedTools}" \\
                --permission-mode bypassPermissions \\
                --output-format json \\
                --json-schema claude-review-schema.json \\
                > claude-output.json
        """
    }

    logUsage()

    sh "jq '.structured_output' claude-output.json > review.json"
    def structuredOutput = readFile('review.json').trim()

    if (structuredOutput == 'null') {
        postComment(provider, repoSlug, prId, scmCredentialsId,
            'Claude review did not return a valid structured result. Check the build log.')
        return
    }

    def summary = sh(script: "jq -r '.summary' review.json", returnStdout: true).trim()
    def risk    = sh(script: "jq -r '.risk_level' review.json", returnStdout: true).trim()
    sh "jq -r '.findings[] | \"- \" + .file + \":\" + (.line|tostring) + \" [\" + .severity + \"]: \" + .description' review.json > findings.md"
    def findingsMd = readFile('findings.md').trim()

    def body = "**Risk level: ${risk}**\n\n${summary}"
    if (findingsMd) {
        body += "\n\nFindings:\n\n${findingsMd}"
    }

    postComment(provider, repoSlug, prId, scmCredentialsId, body)
}

private void logUsage() {
    def turns          = sh(script: "jq -r '.num_turns' claude-output.json", returnStdout: true).trim()
    def cost           = sh(script: "jq -r '.total_cost_usd' claude-output.json", returnStdout: true).trim()
    def durationMs     = sh(script: "jq -r '.duration_ms' claude-output.json", returnStdout: true).trim()
    def inputTokens    = sh(script: "jq -r '.usage.input_tokens' claude-output.json", returnStdout: true).trim()
    def outputTokens   = sh(script: "jq -r '.usage.output_tokens' claude-output.json", returnStdout: true).trim()
    def cacheRead      = sh(script: "jq -r '.usage.cache_read_input_tokens' claude-output.json", returnStdout: true).trim()
    def cacheCreation  = sh(script: "jq -r '.usage.cache_creation_input_tokens' claude-output.json", returnStdout: true).trim()

    echo "Claude review usage: turns=${turns} duration=${durationMs}ms input_tokens=${inputTokens} output_tokens=${outputTokens} cache_read_tokens=${cacheRead} cache_creation_tokens=${cacheCreation} cost_usd=${cost}"
}

private void postComment(String provider, String repoSlug, String prId, String scmCredentialsId, String body) {
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
