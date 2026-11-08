#!/usr/bin/env bash
set -uo pipefail
mkdir -p build/ci-diagnostics
cat > build/ci-diagnostics/progress.gradle <<'GRADLE'
allprojects {
    tasks.withType(Test).configureEach {
        beforeSuite { suite ->
            if (suite.className != null) logger.lifecycle("CI_SUITE_START ${suite.className}")
        }
        afterSuite { suite, result ->
            if (suite.className != null) logger.lifecycle("CI_SUITE_END ${suite.className}: ${result.resultType} (${result.testCount} tests)")
        }
    }
}
GRADLE
chmod +x ./gradlew
timeout --signal=TERM --kill-after=20s 8m ./gradlew -I build/ci-diagnostics/progress.gradle \
    :app:testDebugUnitTest -PskipLocalGithubToken=true -PskipLocalCricketConfiguration=true \
    > build/ci-diagnostics/unit-tests.log 2>&1 &
build_pid=$!
elapsed=0
printed=0
while kill -0 "$build_pid" 2>/dev/null; do
    sleep 30
    elapsed=$((elapsed + 30))
    lines=$(wc -l < build/ci-diagnostics/unit-tests.log)
    if (( lines > printed )); then sed -n "$((printed + 1)),${lines}p" build/ci-diagnostics/unit-tests.log; printed=$lines; fi
    if (( elapsed >= 180 && elapsed % 60 == 0 )); then
        while read -r worker_pid; do
            [[ -z "$worker_pid" ]] && continue
            echo "Capturing test worker $worker_pid at ${elapsed}s"
            timeout 15s jcmd "$worker_pid" Thread.print > "build/ci-diagnostics/worker-${worker_pid}-${elapsed}.txt" 2>&1 || true
        done < <(jps -l | awk '/GradleWorkerMain/ {print $1}')
    fi
done
wait "$build_pid"
result=$?
sed -n "$((printed + 1)),\$p" build/ci-diagnostics/unit-tests.log
exit "$result"
