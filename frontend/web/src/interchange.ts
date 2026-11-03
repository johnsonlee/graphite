// Write the single interchange JSON document one record at a time.
import fs from 'node:fs';
import type { GraphNode, SourceGraph } from './analyzer.js';

function encodeNode(node: GraphNode): string {
  // JSON.stringify(-0) would change the value the Rust importer receives.
  if (node.kind === 'DoubleConstant') {
    if (!Number.isFinite(node.value)) throw new Error('Non-finite DoubleConstant in interchange');
    if (Object.is(node.value, -0)) return `${JSON.stringify({ ...node, value: undefined }).slice(0, -1)},"value":-0}`;
  }
  return JSON.stringify(node);
}

function writeRecords(fd: number, graph: SourceGraph): void {
  const buffer = Buffer.allocUnsafe(64 * 1024);
  const encoder = new TextEncoder();
  let used = 0;
  function flush(): void {
    let offset = 0;
    while (offset < used) {
      const n = fs.writeSync(fd, buffer, offset, used - offset);
      if (n <= 0) throw new Error('Interchange write made no progress');
      offset += n;
    }
    used = 0;
  }
  function emit(text: string): void {
    let pos = 0;
    while (pos < text.length) {
      const { read, written } = encoder.encodeInto(text.slice(pos), buffer.subarray(used));
      pos += read;
      used += written;
      // A scalar may need more bytes than remain in the buffer.
      if (pos < text.length || used === buffer.length) flush();
    }
  }
  function array<T>(field: string, values: readonly T[], encode: (value: T) => string): void {
    emit(`,"${field}":[`);
    for (const [i, value] of values.entries()) {
      if (i) emit(',');
      emit(encode(value));
    }
    emit(']');
  }
  emit(`{"version":${graph.version}`);
  array('methods', graph.methods, JSON.stringify);
  array('nodes', graph.nodes, encodeNode);
  array('edges', graph.edges, JSON.stringify);
  array('classOrigins', graph.classOrigins, JSON.stringify);
  emit('}');
  flush();
}

export function writeInterchange(output: string, graph: SourceGraph): void {
  const fd = fs.openSync(output, 'wx');
  let created: fs.BigIntStats | undefined;
  try {
    try {
      created = fs.fstatSync(fd, { bigint: true });
      writeRecords(fd, graph);
    } finally {
      fs.closeSync(fd);
    }
  } catch (error) {
    // A failed write normally owns this private staging path; a replacement does not belong to us.
    const live = fs.lstatSync(output, { bigint: true, throwIfNoEntry: false });
    if (created && live && live.dev === created.dev && live.ino === created.ino) fs.unlinkSync(output);
    throw error;
  }
}
