<#
.SYNOPSIS
Runs the local checks behind the authorizer design choices (see README.md in this folder).

.DESCRIPTION
1. Compiles the authorizer in this repository and asks Maven for its test classpath, so every
   check runs against exactly the jars the project builds with (Kafka, Log4j).
2. Runs the selected Java programs in this folder with Java's single-file launcher.
3. Saves the complete output to results\run-<timestamp>.txt (ignored by Git).

Requirements: JDK 17+ (java on PATH), Maven (mvn on PATH), network access for Maven
the first time. No Docker or Kubernetes needed.

.EXAMPLE
.\Run-Spikes.ps1
.\Run-Spikes.ps1 -Only Strategies
.\Run-Spikes.ps1 -Only Strategies -Threads 1
#>
param(
    [string]$Repo = (Split-Path -Parent $PSScriptRoot),
    [string]$Log4jConfig = (Join-Path $PSScriptRoot 'log4j2-audit.properties'),
    [ValidateSet('All', 'Log4j', 'AclRace', 'NpeCheck', 'Strategies')]
    [string]$Only = 'All',
    # Number of request threads in AuthzSpike (Kafka's default num.io.threads is 8).
    [int]$Threads = 8
)

$here = $PSScriptRoot
$work = Join-Path $here 'work'
$results = Join-Path $here 'results'
New-Item -ItemType Directory -Force $work, $results | Out-Null
$resultFile = Join-Path $results ("run-{0:yyyyMMdd-HHmmss}.txt" -f (Get-Date))

function Invoke-JavaFile([string]$file, [string]$classpath, [string[]]$jvmArgs = @()) {
    & java -Xmx2g @jvmArgs -cp $classpath (Join-Path $here $file) 2>&1 |
        Where-Object { "$_" -notmatch '^SLF4J' } | ForEach-Object { "$_" }
}

& {
    "== Environment"
    "Date: {0:o}" -f (Get-Date)
    "Repo: branch {0}, commit {1}" -f (git -C $Repo rev-parse --abbrev-ref HEAD), (git -C $Repo rev-parse --short HEAD)
    "Processors: {0}" -f [Environment]::ProcessorCount
    & java -version 2>&1 | ForEach-Object { "$_" }

    "`n== Compiling the authorizer and resolving its classpath"
    & mvn -B -q -f (Join-Path $Repo 'pom.xml') compile dependency:build-classpath "-Dmdep.outputFile=$work\cp.txt" "-Dmdep.includeScope=test"
    if ($LASTEXITCODE -ne 0) { throw 'Maven failed' }
    $cp = (Get-Content (Join-Path $work 'cp.txt') -Raw).Trim()
    $cpWithClasses = $cp + ';' + (Join-Path $Repo 'target\classes')

    if ($Only -in 'All', 'Log4j') {
        "`n== Log4j: $(Split-Path -Leaf $Log4jConfig)"
        $logDir = Join-Path $work 'logs'
        Remove-Item -Recurse -Force $logDir -ErrorAction SilentlyContinue
        New-Item -ItemType Directory -Force $logDir | Out-Null
        $local = ($logDir -replace '\\', '/') + '/'
        $propsFile = Join-Path $work 'log4j2.properties'
        $props = Get-Content $Log4jConfig | ForEach-Object { $_.Replace('/mnt/logs/', $local) }
        [IO.File]::WriteAllLines($propsFile, [string[]]$props)
        $uri = 'file:///' + ($propsFile -replace '\\', '/')
        '-- console output (expected: only the two console-only lines, no Log4j WARN/ERROR):'
        Invoke-JavaFile 'Log4jConfigCheck.java' $cp @("-Dlog4j.configurationFile=$uri", '-Dlog4j2.statusLoggerLevel=WARN')
        '-- kafka-authorizer.log (expected: the three Principal lines only):'
        Get-Content (Join-Path $logDir 'kafka-authorizer.log') | ForEach-Object { "$_" }
    }

    if ($Only -in 'All', 'AclRace') {
        "`n== AclRace: PR #83 pattern on a shared StandardAuthorizer"
        Invoke-JavaFile 'AclRace.java' $cp
    }

    if ($Only -in 'All', 'NpeCheck') {
        "`n== NpeCheck: authorizeByResourceType on the checked-out CheetahKRaftAuthorizer"
        Invoke-JavaFile 'NpeCheck.java' $cpWithClasses
    }

    if ($Only -in 'All', 'Strategies') {
        "`n== AuthzSpike: thread-safety strategies, $Threads request threads (takes about 2 minutes)"
        Invoke-JavaFile 'AuthzSpike.java' $cp @("-Dspike.threads=$Threads")
    }
} | Tee-Object -FilePath $resultFile

"`nFull output saved to $resultFile"
