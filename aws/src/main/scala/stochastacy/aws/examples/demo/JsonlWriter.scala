package stochastacy.aws.examples.demo

import java.io.BufferedWriter
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

/**
 * An incremental JSONL sink: one buffered line per [[DemoRecord]], flushed to disk as records are written
 * (so the file grows during a run rather than materializing in memory at the end). Not thread-safe — the
 * streaming runner writes from a single, order-preserving stage.
 */
final class JsonlWriter private (writer: BufferedWriter):
  private var written: Long = 0L

  def write(record: DemoRecord): Unit =
    writer.write(JsonlExport.line(record))
    writer.newLine()
    written += 1

  def writeAll(records: IterableOnce[DemoRecord]): Unit = records.iterator.foreach(write)

  def count: Long = written

  def close(): Unit =
    writer.flush()
    writer.close()

object JsonlWriter:
  def open(path: Path): JsonlWriter =
    new JsonlWriter(Files.newBufferedWriter(path, StandardCharsets.UTF_8))
