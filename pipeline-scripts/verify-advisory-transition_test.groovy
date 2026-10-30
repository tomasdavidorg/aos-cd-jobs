def runJob(String path, Map parameters) {
    def calls = [shell: [], locks: [], properties: []]
    def commonlib = new Expando()
    ['describeJob', 'checkMock', 'safeArchiveArtifacts'].each { name -> commonlib[name] = { Object... values -> } }
    ['ocpVersionParam', 'artToolsParam', 'dryrunParam', 'mockParam'].each { name ->
        commonlib[name] = { Object... values -> [:] }
    }
    def buildlib = new Expando(commonlib: commonlib)
    ['kinit', 'init_artcd_working_dir', 'cleanWorkspace'].each { name -> buildlib[name] = { -> } }
    def binding = new Binding([
        params: parameters, scm: [:], WORKSPACE: '/inert workspace',
        currentBuild: [number: 1],
        load: { String ignored -> buildlib },
        checkout: { Object ignored -> },
        echo: { Object ignored -> },
        error: { String message -> throw new IllegalArgumentException(message) },
        properties: { List values -> calls.properties.addAll(values) },
        disableConcurrentBuilds: { -> 'serialized' },
        disableResume: { -> 'no-resume' },
        buildDiscarder: { Object ignored -> [:] },
        logRotator: { Map values -> values },
        string: { Map values -> values },
        sh: { Map values -> calls.shell << values.script },
        lock: { Map values, Closure body -> calls.locks << values; body() },
    ])
    ['node', 'timestamps', 'stage', 'sshagent', 'withCredentials'].each { name ->
        binding.setVariable(name, { Object... values -> values[-1].call() })
    }
    new GroovyShell(binding).evaluate(new File(path))
    calls
}

def job = 'jobs/verify/verify-advisory-transition/Jenkinsfile'
def base = [BUILD_VERSION: '4.22', ASSEMBLY: '4.22.9', SHIPMENT_MR_IID: '42', DRY_RUN: false]
def cases = ['4.22.9', "release with spaces ' single and \" double; \$(never-run)"]
cases.each { assembly ->
    def calls = runJob(job, base + [ASSEMBLY: assembly])
    assert calls.shell.size() == 1
    assert calls.locks == [[resource: "verify-advisory-transition-4.22-${assembly}", skipIfLocked: true]]
    println 'ASSEMBLY=' + assembly.bytes.encodeBase64()
    println 'COMMAND=' + calls.shell[0].bytes.encodeBase64()
}
def dry = runJob(job, base + [DRY_RUN: true])
assert dry.shell[0].contains("'--dry-run'")
[null, '', '0', '-1', '1.5', '42; never-run', "4'2", ' 42 '].each { iid ->
    try {
        runJob(job, base + [SHIPMENT_MR_IID: iid])
        assert false: "Accepted invalid IID: ${iid}"
    } catch (IllegalArgumentException expected) {
        assert expected.message == 'SHIPMENT_MR_IID must be a positive integer'
    }
}
def scanner = runJob('scheduled-jobs/build/trigger-verify-advisory-transition/Jenkinsfile', [:])
assert scanner.properties.contains('serialized')
assert scanner.shell.size() == 1
assert scanner.shell[0].endsWith('schedule-verify-advisory-transition-scan')
println 'PASS: actual Jenkinsfile consumers, positive IID, dry-run, per-release lock and serialized scanner'
