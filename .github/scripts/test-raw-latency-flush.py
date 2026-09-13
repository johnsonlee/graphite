#!/usr/bin/env python3
"""Execute the actual Kotlin phase method with bounded replay/verification stubs.

Usage: --java-home <JDK17> --compiler-classpath <Kotlin compiler dependencies>
       --runtime-classpath <Kotlin stdlib JAR>
No graphs, benchmark measurements, or latency assertions are involved.
"""
import argparse
from pathlib import Path
import select
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'graphite-webgraph/src/jmh/kotlin/io/johnsonlee/graphite/webgraph/LargeBroadQueryPressureBenchmark.kt'


def probe(method):
    return '''import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path
class BroadQueryCase
class QueryCorrectnessRecord
class Sample(val responseBytes: Long = 1, val rowCount: Long = 1) {
    fun correctnessRecord() = QueryCorrectnessRecord()
}
object QueryCorrectnessManifest {
    fun verify(expected: List<QueryCorrectnessRecord>, actual: List<QueryCorrectnessRecord>) {
        if (System.getProperty("mismatch") == "true") error("result mismatch")
    }
}
class Probe {
    private val DIVERSE_PHASE_MIN_NANOS = 10000000000L
    private var calls = 0
    private fun observationRow(sample: Sample) = "complete-result"
    private fun replay(validate: Boolean, cases: List<BroadQueryCase>): List<Sample> {
        calls++
        if (calls == 2) {
            println("BLOCKED_NEXT_CALL")
            Thread.sleep(60000)
        }
        return listOf(Sample())
    }
''' + method + '''
    fun run(writer: BufferedWriter) {
        replayLatencyPhase(BroadQueryCase(), listOf(QueryCorrectnessRecord()), "measurement", 40, writer)
    }
}
fun main(args: Array<String>) {
    // Deliberately no close/use: verify what is visible before stack cleanup or cancellation.
    val writer = Files.newBufferedWriter(Path.of(args[0]))
    try { Probe().run(writer) } catch (failure: IllegalStateException) {
        check(failure.message == "result mismatch")
        println("RESULT_MISMATCH")
        Thread.sleep(60000)
    }
}
'''


def observe(java, classpath, output, mismatch):
    command = [str(java), '-Dmismatch=' + str(mismatch).lower(), '-cp', classpath, 'ProbeKt', str(output)]
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    try:
        assert select.select([process.stdout], [], [], 10)[0], 'Probe did not reach bounded checkpoint'
        marker = process.stdout.readline().strip()
        assert marker == ('RESULT_MISMATCH' if mismatch else 'BLOCKED_NEXT_CALL'), marker
        before = output.read_bytes()
        process.kill()  # No writer.close(), query completion, or shutdown hook can rescue buffered rows.
        process.wait(timeout=5)
        assert output.read_bytes() == before, 'Cancellation unexpectedly changed the completed evidence'
        return before
    finally:
        if process.poll() is None:
            process.kill()
            process.wait(timeout=5)
        process.stdout.close()
        process.stderr.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for arg in ('java-home', 'compiler-classpath', 'runtime-classpath'):
        parser.add_argument('--' + arg, required=True)
    args = parser.parse_args()
    java = Path(args.java_home) / 'bin/java'
    source = SOURCE.read_text()
    method = source[source.index('    private fun replayLatencyPhase('):source.index('    private fun latencyShardWorkload(')]
    assert method.count('writer.flush()') == 1
    with tempfile.TemporaryDirectory(prefix='raw-flush-test-') as temp:
        root = Path(temp)
        for mutation in (False, True):
            directory = root / ('without-flush' if mutation else 'actual')
            directory.mkdir()
            kotlin = directory / 'Probe.kt'
            kotlin.write_text(probe(method.replace('writer.flush()', '') if mutation else method))
            classes = directory / 'classes'
            subprocess.run([str(java), '-cp', args.compiler_classpath,
                            'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
                            '-jvm-target', '17', '-classpath', args.runtime_classpath,
                            '-d', str(classes), str(kotlin)], check=True, capture_output=True, text=True)
            for mismatch in (False, True):
                data = observe(java, str(classes) + ':' + args.runtime_classpath,
                               directory / ('mismatch.tsv' if mismatch else 'cancel.tsv'), mismatch)
                if mutation:
                    assert data == b'', 'Pre-fix mutation unexpectedly retained the buffered sample'
                else:
                    assert data.endswith(b'\tcomplete-result\n'), data
                    fields = data.decode().splitlines()[0].split('\t')
                    assert len(data.splitlines()) == 1 and fields[:2] == ['measurement', '1']
                    assert int(fields[2]) > 0
    print('PASS: completed row survives next-call cancellation and result mismatch; removing flush reproduces both losses')


if __name__ == '__main__':
    main()
