package com.fulcrumgenomics.jlibdeflate;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Benchmarks libdeflate vs. JDK zlib for BGZF (64KB block) compression and
 * decompression.
 *
 * <p>Three I/O modes are tested for each engine:
 * <ul>
 *   <li><b>In-Memory</b> — data is pre-buffered; measures pure codec throughput</li>
 *   <li><b>Read I/O</b> — reads input from disk, processes in memory</li>
 *   <li><b>Full I/O</b> — reads from disk, processes, writes output to disk</li>
 * </ul>
 *
 * <p>Usage: {@code java -jar jlibdeflate.jar benchmark --input <file> [--level <0-12>] [--iterations <n>]}
 */
public class LibdeflateBenchmark {

    /** Size of the BGZF gzip header (standard 10-byte gzip header + 8-byte BGZF extra field). */
    private static final int BGZF_HEADER_SIZE = 18;

    /** Size of the gzip trailer (CRC32 + ISIZE). */
    private static final int BGZF_TRAILER_SIZE = 8;

    /** Maximum total size of a single BGZF block (spec limit, fits in 16-bit BSIZE + 1). */
    private static final int MAX_BGZF_BLOCK_SIZE = 65536;

    /** Maximum compressed payload that fits in a BGZF block. */
    private static final int MAX_BGZF_CDATA = MAX_BGZF_BLOCK_SIZE - BGZF_HEADER_SIZE - BGZF_TRAILER_SIZE;

    /** Fixed BGZF header bytes — everything except BSIZE at offsets 16-17. */
    private static final byte[] BGZF_HEADER = {
        0x1f,
        (byte) 0x8b, // ID1, ID2
        0x08, // CM = deflate
        0x04, // FLG = FEXTRA
        0x00,
        0x00,
        0x00,
        0x00, // MTIME
        0x00, // XFL
        (byte) 0xff, // OS = unknown
        0x06,
        0x00, // XLEN = 6 (little-endian)
        0x42,
        0x43, // SI1='B', SI2='C'
        0x02,
        0x00, // SLEN = 2
        0x00,
        0x00 // BSIZE placeholder (filled per block)
    };

    /** Standard 28-byte BGZF EOF marker (empty BGZF block). */
    private static final byte[] BGZF_EOF = {
        0x1f,
        (byte) 0x8b,
        0x08,
        0x04,
        0x00,
        0x00,
        0x00,
        0x00,
        0x00,
        (byte) 0xff,
        0x06,
        0x00,
        0x42,
        0x43,
        0x02,
        0x00,
        0x1b,
        0x00,
        0x03,
        0x00,
        0x00,
        0x00,
        0x00,
        0x00,
        0x00,
        0x00,
        0x00,
        0x00
    };

    /**
     * Entry point for the benchmark subcommand. Parses command-line options and
     * runs the benchmark suite.
     *
     * @param args command-line arguments (after the "benchmark" subcommand has been stripped)
     * @throws Exception on I/O errors or invalid arguments
     */
    public static void main(String[] args) throws Exception {
        Path inputFile = null;
        int level = LibdeflateCompressor.DEFAULT_LEVEL;
        int iterations = 5;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--input":
                    inputFile = Paths.get(args[++i]);
                    break;
                case "--level":
                    level = Integer.parseInt(args[++i]);
                    break;
                case "--iterations":
                    iterations = Integer.parseInt(args[++i]);
                    break;
                case "--help":
                case "-h":
                    printUsage();
                    return;
                default:
                    System.err.println("Unknown option: " + args[i]);
                    printUsage();
                    System.exit(1);
            }
        }

        if (inputFile == null) {
            System.err.println("Error: --input is required");
            printUsage();
            System.exit(1);
        }

        if (!Files.isRegularFile(inputFile)) {
            System.err.println("Error: input file does not exist or is not a regular file: " + inputFile);
            System.exit(1);
        }

        if (level < LibdeflateCompressor.MIN_LEVEL || level > LibdeflateCompressor.MAX_LEVEL) {
            System.err.println("Error: level must be between " + LibdeflateCompressor.MIN_LEVEL + " and "
                    + LibdeflateCompressor.MAX_LEVEL);
            System.exit(1);
        }

        if (iterations < 1) {
            System.err.println("Error: iterations must be >= 1");
            System.exit(1);
        }

        run(inputFile, level, iterations);
    }

    /** Prints usage information for the benchmark subcommand to stderr. */
    private static void printUsage() {
        System.err.println(
                "Usage: java -jar jlibdeflate.jar benchmark --input <file> [--level <0-12>] [--iterations <n>]");
        System.err.println();
        System.err.println("Options:");
        System.err.println("  --input <file>       Uncompressed input file (required)");
        System.err.println("  --level <0-12>       Compression level (default: 6)");
        System.err.println("  --iterations <n>     Number of benchmark iterations (default: 5)");
    }

    /**
     * Returns a platform identifier string, e.g. "osx/aarch64" or "linux/x86_64".
     * Normalizes common JVM os.name and os.arch values to shorter canonical forms.
     */
    private static String detectPlatform() {
        String osName = System.getProperty("os.name", "unknown").toLowerCase();
        String os;
        if (osName.contains("mac") || osName.contains("darwin")) {
            os = "osx";
        } else if (osName.contains("linux")) {
            os = "linux";
        } else if (osName.contains("win")) {
            os = "windows";
        } else {
            os = osName;
        }

        String archName = System.getProperty("os.arch", "unknown").toLowerCase();
        String arch;
        if (archName.equals("amd64") || archName.equals("x86_64")) {
            arch = "x86_64";
        } else if (archName.equals("aarch64") || archName.equals("arm64")) {
            arch = "aarch64";
        } else {
            arch = archName;
        }

        return os + "/" + arch;
    }

    /**
     * Computes the largest uncompressed block size whose worst-case compressed output
     * fits within a BGZF block. Starts at 65536 and decreases in 256-byte steps
     * until {@link LibdeflateCompressor#deflateCompressBound(int)} fits within
     * {@link #MAX_BGZF_CDATA}.
     *
     * @param level the compression level to use for bound calculation
     * @return the maximum safe uncompressed block size
     * @throws IllegalStateException if no valid block size is found
     */
    private static int computeMaxBlockSize(int level) {
        try (var comp = new LibdeflateCompressor(level)) {
            for (int size = 65536; size > 0; size -= 256) {
                if (comp.deflateCompressBound(size) <= MAX_BGZF_CDATA) {
                    return size;
                }
            }
        }
        throw new IllegalStateException("Cannot find a block size that fits in BGZF");
    }

    /** Holds the uncompressed size and raw DEFLATE compressed payload for a single BGZF block. */
    private static final class CompressedBlock {
        final int uncompressedSize;
        final byte[] data;

        /**
         * @param uncompressedSize the original uncompressed size of this block in bytes
         * @param data the raw DEFLATE compressed payload
         */
        CompressedBlock(int uncompressedSize, byte[] data) {
            this.uncompressedSize = uncompressedSize;
            this.data = data;
        }
    }

    /**
     * Prints a progress dot to stderr and flushes immediately. Used to indicate
     * that a benchmark measurement has completed.
     */
    private static void tick() {
        System.err.print(".");
        System.err.flush();
    }

    /**
     * Runs the full benchmark suite: prepares data, executes all compression and
     * decompression benchmarks across I/O modes, and prints result tables to stdout.
     * Progress is reported to stderr as dots.
     *
     * @param inputFile path to the uncompressed input file
     * @param level compression level (0–12)
     * @param iterations number of timed iterations per benchmark
     * @throws Exception on I/O errors
     */
    private static void run(Path inputFile, int level, int iterations) throws Exception {
        int blockSize = computeMaxBlockSize(level);

        // Read uncompressed input into blocks and buffer in memory
        List<byte[]> uncompressedBlocks = readBlocks(inputFile, blockSize);

        // Pre-compress all blocks with libdeflate and write to a BGZF temp file
        Path compressedFile = Files.createTempFile("jlibdeflate-bench-", ".bgzf");
        compressedFile.toFile().deleteOnExit();
        writeCompressedFile(compressedFile, uncompressedBlocks, level);

        // Also buffer compressed blocks in memory for in-memory decompression
        List<CompressedBlock> compressedBlocks = readCompressedBlocks(compressedFile);

        long totalUncompressedBytes = 0;
        for (byte[] block : uncompressedBlocks) {
            totalUncompressedBytes += block.length;
        }

        long compressedSize = Files.size(compressedFile);

        // Create a temp output file for full-I/O benchmarks
        Path outputFile = Files.createTempFile("jlibdeflate-bench-out-", ".bin");
        outputFile.toFile().deleteOnExit();

        System.out.printf(
                "Benchmark: %s, platform=%s, level=%d, iterations=%d, block_size=%d%n",
                inputFile.getFileName(), detectPlatform(), level, iterations, blockSize);
        System.out.printf(
                "File size: %,d bytes uncompressed, %,d bytes compressed (%.1f%%)%n",
                totalUncompressedBytes, compressedSize, 100.0 * compressedSize / totalUncompressedBytes);
        System.out.println();

        // Clamp JDK level to 0-9
        int jdkLevel = Math.min(level, 9);
        if (jdkLevel != level) {
            System.out.printf("Note: JDK Deflater max level is 9; using %d instead of %d%n%n", jdkLevel, level);
        }

        // 12 total benchmarks: 2 engines x 3 I/O modes x 2 directions (compress + decompress)
        System.err.print("Running 12 benchmarks: ");

        // ---- Compression benchmarks ----
        BlockCompressor jdkComp = new JdkCompressor(jdkLevel);
        BlockCompressor libComp = new LibdeflateByteArrayCompressor(level);

        String[] ioModes = {"In-Memory", "Read", "Read + Write"};
        double[] jdkCompMbps = new double[3];
        double[] libCompMbps = new double[3];

        jdkCompMbps[0] = benchmarkCompressionInMemory(uncompressedBlocks, iterations, totalUncompressedBytes, jdkComp);
        tick();
        libCompMbps[0] = benchmarkCompressionInMemory(uncompressedBlocks, iterations, totalUncompressedBytes, libComp);
        tick();
        jdkCompMbps[1] = benchmarkCompressionReadIO(inputFile, blockSize, iterations, totalUncompressedBytes, jdkComp);
        tick();
        libCompMbps[1] = benchmarkCompressionReadIO(inputFile, blockSize, iterations, totalUncompressedBytes, libComp);
        tick();
        jdkCompMbps[2] = benchmarkCompressionFullIO(
                inputFile, blockSize, outputFile, iterations, totalUncompressedBytes, jdkComp);
        tick();
        libCompMbps[2] = benchmarkCompressionFullIO(
                inputFile, blockSize, outputFile, iterations, totalUncompressedBytes, libComp);
        tick();

        // ---- Decompression benchmarks ----
        BlockDecompressor jdkDecomp = new JdkDecompressor();
        BlockDecompressor libDecomp = new LibdeflateByteArrayDecompressor();

        double[] jdkDecompMbps = new double[3];
        double[] libDecompMbps = new double[3];

        jdkDecompMbps[0] =
                benchmarkDecompressionInMemory(compressedBlocks, iterations, totalUncompressedBytes, jdkDecomp);
        tick();
        libDecompMbps[0] =
                benchmarkDecompressionInMemory(compressedBlocks, iterations, totalUncompressedBytes, libDecomp);
        tick();
        jdkDecompMbps[1] = benchmarkDecompressionReadIO(compressedFile, iterations, totalUncompressedBytes, jdkDecomp);
        tick();
        libDecompMbps[1] = benchmarkDecompressionReadIO(compressedFile, iterations, totalUncompressedBytes, libDecomp);
        tick();
        jdkDecompMbps[2] =
                benchmarkDecompressionFullIO(compressedFile, outputFile, iterations, totalUncompressedBytes, jdkDecomp);
        tick();
        libDecompMbps[2] =
                benchmarkDecompressionFullIO(compressedFile, outputFile, iterations, totalUncompressedBytes, libDecomp);
        tick();
        System.err.println(" done.");

        // ---- Print results ----
        System.out.println("Compression:");
        Table compTable = new Table("Engine", "I/O Mode", "MB/sec", "Vs. JDK");
        for (int i = 0; i < 3; i++) {
            compTable.addRow("JDK Deflater", ioModes[i], String.format("%.1f", jdkCompMbps[i]), "");
            compTable.addRow(
                    "libdeflate",
                    ioModes[i],
                    String.format("%.1f", libCompMbps[i]),
                    String.format("%.2fx", libCompMbps[i] / jdkCompMbps[i]));
        }
        compTable.print();
        System.out.println();

        System.out.println("Decompression:");
        Table decompTable = new Table("Engine", "I/O Mode", "MB/sec", "Vs. JDK");
        for (int i = 0; i < 3; i++) {
            decompTable.addRow("JDK Inflater", ioModes[i], String.format("%.1f", jdkDecompMbps[i]), "");
            decompTable.addRow(
                    "libdeflate",
                    ioModes[i],
                    String.format("%.1f", libDecompMbps[i]),
                    String.format("%.2fx", libDecompMbps[i] / jdkDecompMbps[i]));
        }
        decompTable.print();

        Files.deleteIfExists(compressedFile);
        Files.deleteIfExists(outputFile);
    }

    // ---- File I/O helpers ----

    /**
     * Reads an uncompressed file into a list of byte array blocks, each up to
     * {@code blockSize} bytes. The last block may be shorter.
     *
     * @param path path to the file to read
     * @param blockSize maximum number of bytes per block
     * @return list of byte arrays, one per block
     * @throws IOException on read errors
     */
    private static List<byte[]> readBlocks(Path path, int blockSize) throws IOException {
        List<byte[]> blocks = new ArrayList<>();
        try (var in = new BufferedInputStream(new FileInputStream(path.toFile()), blockSize * 4)) {
            byte[] buf = new byte[blockSize];
            int n;
            while ((n = readFully(in, buf)) > 0) {
                byte[] block = new byte[n];
                System.arraycopy(buf, 0, block, 0, n);
                blocks.add(block);
            }
        }
        return blocks;
    }

    /**
     * Reads up to {@code buf.length} bytes from the stream, blocking until that
     * many bytes are available or EOF is reached.
     *
     * @param in the input stream to read from
     * @param buf the buffer to fill
     * @return the number of bytes actually read (0 at EOF)
     * @throws IOException on read errors
     */
    private static int readFully(java.io.InputStream in, byte[] buf) throws IOException {
        int totalRead = 0;
        while (totalRead < buf.length) {
            int n = in.read(buf, totalRead, buf.length - totalRead);
            if (n < 0) break;
            totalRead += n;
        }
        return totalRead;
    }

    /**
     * Compresses a list of uncompressed blocks with libdeflate and writes the result
     * as a valid BGZF file, terminated with the standard EOF marker.
     *
     * @param path output file path
     * @param blocks list of uncompressed data blocks
     * @param level compression level (0–12)
     * @throws IOException on write errors
     */
    private static void writeCompressedFile(Path path, List<byte[]> blocks, int level) throws IOException {
        CRC32 crc32 = new CRC32();
        try (var comp = new LibdeflateCompressor(level);
                var out = new BufferedOutputStream(new FileOutputStream(path.toFile()))) {
            for (byte[] block : blocks) {
                byte[] cdata = comp.deflateCompress(block);
                writeBgzfBlock(out, cdata, block, crc32);
            }
            out.write(BGZF_EOF);
        }
    }

    /**
     * Writes a single BGZF block to the output stream. A BGZF block consists of an
     * 18-byte gzip header (with the BC extra field containing BSIZE), the raw DEFLATE
     * compressed data, and an 8-byte gzip trailer (CRC32 + ISIZE), all little-endian.
     *
     * @param out the output stream to write to
     * @param cdata the raw DEFLATE compressed payload
     * @param uncompressed the original uncompressed data (used to compute CRC32 and ISIZE)
     * @param crc32 a reusable CRC32 instance (reset internally)
     * @throws IOException on write errors
     */
    private static void writeBgzfBlock(OutputStream out, byte[] cdata, byte[] uncompressed, CRC32 crc32)
            throws IOException {
        int blockSize = BGZF_HEADER_SIZE + cdata.length + BGZF_TRAILER_SIZE;
        int bsize = blockSize - 1;

        byte[] header = BGZF_HEADER.clone();
        header[16] = (byte) (bsize & 0xff);
        header[17] = (byte) ((bsize >>> 8) & 0xff);
        out.write(header);

        out.write(cdata);

        crc32.reset();
        crc32.update(uncompressed);
        int crcValue = (int) crc32.getValue();
        int isize = uncompressed.length;
        byte[] trailer = new byte[8];
        trailer[0] = (byte) (crcValue & 0xff);
        trailer[1] = (byte) ((crcValue >>> 8) & 0xff);
        trailer[2] = (byte) ((crcValue >>> 16) & 0xff);
        trailer[3] = (byte) ((crcValue >>> 24) & 0xff);
        trailer[4] = (byte) (isize & 0xff);
        trailer[5] = (byte) ((isize >>> 8) & 0xff);
        trailer[6] = (byte) ((isize >>> 16) & 0xff);
        trailer[7] = (byte) ((isize >>> 24) & 0xff);
        out.write(trailer);
    }

    /**
     * Reads a BGZF file and extracts the raw DEFLATE payload and uncompressed size
     * (from ISIZE in the gzip trailer) for each block. The EOF marker block is
     * consumed but not included in the returned list.
     *
     * @param path path to the BGZF file
     * @return list of compressed blocks with their uncompressed sizes
     * @throws IOException on read errors or if the file is truncated
     */
    private static List<CompressedBlock> readCompressedBlocks(Path path) throws IOException {
        List<CompressedBlock> blocks = new ArrayList<>();
        try (var in = new BufferedInputStream(new FileInputStream(path.toFile()), MAX_BGZF_BLOCK_SIZE * 4)) {
            byte[] header = new byte[BGZF_HEADER_SIZE];
            while (true) {
                int headerRead = readFully(in, header);
                if (headerRead == 0) break;
                if (headerRead < BGZF_HEADER_SIZE) {
                    throw new IOException("Truncated BGZF header");
                }

                int bsize = (header[16] & 0xff) | ((header[17] & 0xff) << 8);
                int blockSize = bsize + 1;
                int cdataLen = blockSize - BGZF_HEADER_SIZE - BGZF_TRAILER_SIZE;

                if (cdataLen <= 0) {
                    // EOF marker or empty block — consume remaining bytes and stop
                    byte[] trailer = new byte[BGZF_TRAILER_SIZE + cdataLen];
                    readFully(in, trailer);
                    break;
                }

                byte[] cdata = new byte[cdataLen];
                if (readFully(in, cdata) < cdataLen) {
                    throw new IOException("Truncated BGZF compressed data");
                }

                byte[] trailer = new byte[BGZF_TRAILER_SIZE];
                if (readFully(in, trailer) < BGZF_TRAILER_SIZE) {
                    throw new IOException("Truncated BGZF trailer");
                }
                int isize = (trailer[4] & 0xff)
                        | ((trailer[5] & 0xff) << 8)
                        | ((trailer[6] & 0xff) << 16)
                        | ((trailer[7] & 0xff) << 24);

                blocks.add(new CompressedBlock(isize, cdata));
            }
        }
        return blocks;
    }

    // ---- Benchmark helpers ----

    /** Compresses a single uncompressed block into a pre-allocated output buffer. */
    @FunctionalInterface
    private interface BlockCompressor {
        /**
         * @param input the uncompressed input data
         * @param inputLen number of bytes to compress from input
         * @param output pre-allocated output buffer for compressed data
         * @return number of compressed bytes written, or -1 if output is too small
         */
        int compress(byte[] input, int inputLen, byte[] output);
    }

    /** Decompresses a single compressed block into a pre-allocated output buffer. */
    @FunctionalInterface
    private interface BlockDecompressor {
        /**
         * @param input the compressed input data
         * @param inputLen number of bytes of compressed data
         * @param output pre-allocated output buffer for decompressed data
         * @param uncompressedSize the exact expected uncompressed size
         */
        void decompress(byte[] input, int inputLen, byte[] output, int uncompressedSize);
    }

    /**
     * Converts elapsed nanoseconds and total bytes into throughput in MB/sec.
     *
     * @param elapsedNanos wall-clock time in nanoseconds
     * @param totalBytes bytes processed per iteration
     * @param iterations number of iterations
     * @return throughput in megabytes per second (1 MB = 1024 * 1024 bytes)
     */
    private static double toMBps(long elapsedNanos, long totalBytes, int iterations) {
        double seconds = elapsedNanos / 1_000_000_000.0;
        return (totalBytes * iterations) / seconds / (1024.0 * 1024.0);
    }

    /** Simple table renderer using Unicode box-drawing characters. */
    private static final class Table {
        private final String[] headers;
        private final int[] widths;
        private final List<String[]> rows = new ArrayList<>();

        /**
         * Creates a new table with the given column headers. Column widths are
         * initialized to the header lengths and expanded as rows are added.
         *
         * @param headers the column header labels
         */
        Table(String... headers) {
            this.headers = headers;
            this.widths = new int[headers.length];
            for (int i = 0; i < headers.length; i++) {
                widths[i] = headers[i].length();
            }
        }

        /**
         * Adds a data row to the table. Updates column widths if any cell is wider
         * than the current maximum.
         *
         * @param cells the cell values for this row, one per column
         */
        void addRow(String... cells) {
            rows.add(cells);
            for (int i = 0; i < cells.length; i++) {
                widths[i] = Math.max(widths[i], cells[i].length());
            }
        }

        /** Prints the complete table (header, separator, data rows) to stdout. */
        void print() {
            printBorder('\u250c', '\u252c', '\u2510'); // ┌ ┬ ┐
            printDataRow(headers);
            printBorder('\u251c', '\u253c', '\u2524'); // ├ ┼ ┤
            for (String[] row : rows) {
                printDataRow(row);
            }
            printBorder('\u2514', '\u2534', '\u2518'); // └ ┴ ┘
        }

        /**
         * Prints a horizontal border line using the given box-drawing corner/junction characters.
         *
         * @param left the left-edge character (e.g. ┌, ├, or └)
         * @param mid the column-junction character (e.g. ┬, ┼, or ┴)
         * @param right the right-edge character (e.g. ┐, ┤, or ┘)
         */
        private void printBorder(char left, char mid, char right) {
            StringBuilder sb = new StringBuilder();
            sb.append(left);
            for (int i = 0; i < widths.length; i++) {
                if (i > 0) sb.append(mid);
                for (int j = 0; j < widths[i] + 2; j++) sb.append('\u2500'); // ─
            }
            sb.append(right);
            System.out.println(sb);
        }

        /**
         * Prints a single data row with vertical separators. Text columns (indices 0–1)
         * are left-aligned; numeric columns (indices 2+) are right-aligned.
         *
         * @param cells the cell values to print
         */
        private void printDataRow(String[] cells) {
            StringBuilder sb = new StringBuilder();
            sb.append('\u2502'); // │
            for (int i = 0; i < widths.length; i++) {
                sb.append(' ');
                String cell = i < cells.length ? cells[i] : "";
                if (i >= 2) {
                    sb.append(String.format("%" + widths[i] + "s", cell));
                } else {
                    sb.append(String.format("%-" + widths[i] + "s", cell));
                }
                sb.append(' ');
                sb.append('\u2502'); // │
            }
            System.out.println(sb);
        }
    }

    // ---- Compression benchmarks ----

    /**
     * Benchmarks compression with all data pre-loaded in memory. Measures pure
     * codec throughput without any I/O overhead.
     *
     * @param blocks pre-loaded uncompressed data blocks
     * @param iterations number of timed iterations
     * @param totalUncompressedBytes total bytes across all blocks
     * @param compressor the compression engine to benchmark
     * @return throughput in MB/sec
     */
    private static double benchmarkCompressionInMemory(
            List<byte[]> blocks, int iterations, long totalUncompressedBytes, BlockCompressor compressor) {
        byte[] outputBuf = new byte[MAX_BGZF_CDATA];

        long startNanos = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            for (byte[] block : blocks) {
                compressor.compress(block, block.length, outputBuf);
            }
        }
        long elapsedNanos = System.nanoTime() - startNanos;

        return toMBps(elapsedNanos, totalUncompressedBytes, iterations);
    }

    /**
     * Benchmarks compression with input read from disk each iteration. Compressed
     * output is discarded (not written).
     *
     * @param inputFile path to the uncompressed input file
     * @param blockSize block size for reading
     * @param iterations number of timed iterations
     * @param totalUncompressedBytes total uncompressed bytes per iteration
     * @param compressor the compression engine to benchmark
     * @return throughput in MB/sec
     * @throws IOException on read errors
     */
    private static double benchmarkCompressionReadIO(
            Path inputFile, int blockSize, int iterations, long totalUncompressedBytes, BlockCompressor compressor)
            throws IOException {
        byte[] outputBuf = new byte[MAX_BGZF_CDATA];

        long startNanos = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            List<byte[]> blocks = readBlocks(inputFile, blockSize);
            for (byte[] block : blocks) {
                compressor.compress(block, block.length, outputBuf);
            }
        }
        long elapsedNanos = System.nanoTime() - startNanos;

        return toMBps(elapsedNanos, totalUncompressedBytes, iterations);
    }

    /**
     * Benchmarks compression with input read from disk and compressed output written
     * to disk each iteration.
     *
     * @param inputFile path to the uncompressed input file
     * @param blockSize block size for reading
     * @param outputFile path to the output file (overwritten each iteration)
     * @param iterations number of timed iterations
     * @param totalUncompressedBytes total uncompressed bytes per iteration
     * @param compressor the compression engine to benchmark
     * @return throughput in MB/sec
     * @throws IOException on I/O errors
     */
    private static double benchmarkCompressionFullIO(
            Path inputFile,
            int blockSize,
            Path outputFile,
            int iterations,
            long totalUncompressedBytes,
            BlockCompressor compressor)
            throws IOException {
        byte[] outputBuf = new byte[MAX_BGZF_CDATA];

        long startNanos = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            List<byte[]> blocks = readBlocks(inputFile, blockSize);
            try (var out = new BufferedOutputStream(new FileOutputStream(outputFile.toFile()))) {
                for (byte[] block : blocks) {
                    int written = compressor.compress(block, block.length, outputBuf);
                    if (written > 0) out.write(outputBuf, 0, written);
                }
            }
        }
        long elapsedNanos = System.nanoTime() - startNanos;

        return toMBps(elapsedNanos, totalUncompressedBytes, iterations);
    }

    // ---- Decompression benchmarks ----

    /**
     * Benchmarks decompression with all compressed data pre-loaded in memory. Measures
     * pure codec throughput without any I/O overhead.
     *
     * @param blocks pre-loaded compressed blocks with known uncompressed sizes
     * @param iterations number of timed iterations
     * @param totalUncompressedBytes total uncompressed bytes across all blocks
     * @param decompressor the decompression engine to benchmark
     * @return throughput in MB/sec
     */
    private static double benchmarkDecompressionInMemory(
            List<CompressedBlock> blocks, int iterations, long totalUncompressedBytes, BlockDecompressor decompressor) {
        byte[] outputBuf = new byte[MAX_BGZF_BLOCK_SIZE];

        long startNanos = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            for (CompressedBlock block : blocks) {
                decompressor.decompress(block.data, block.data.length, outputBuf, block.uncompressedSize);
            }
        }
        long elapsedNanos = System.nanoTime() - startNanos;

        return toMBps(elapsedNanos, totalUncompressedBytes, iterations);
    }

    /**
     * Benchmarks decompression with compressed data read from disk each iteration.
     * Decompressed output is discarded (not written).
     *
     * @param compressedFile path to the BGZF compressed file
     * @param iterations number of timed iterations
     * @param totalUncompressedBytes total uncompressed bytes per iteration
     * @param decompressor the decompression engine to benchmark
     * @return throughput in MB/sec
     * @throws IOException on read errors
     */
    private static double benchmarkDecompressionReadIO(
            Path compressedFile, int iterations, long totalUncompressedBytes, BlockDecompressor decompressor)
            throws IOException {
        byte[] outputBuf = new byte[MAX_BGZF_BLOCK_SIZE];

        long startNanos = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            List<CompressedBlock> blocks = readCompressedBlocks(compressedFile);
            for (CompressedBlock block : blocks) {
                decompressor.decompress(block.data, block.data.length, outputBuf, block.uncompressedSize);
            }
        }
        long elapsedNanos = System.nanoTime() - startNanos;

        return toMBps(elapsedNanos, totalUncompressedBytes, iterations);
    }

    /**
     * Benchmarks decompression with compressed data read from disk and decompressed
     * output written to disk each iteration.
     *
     * @param compressedFile path to the BGZF compressed file
     * @param outputFile path to the output file (overwritten each iteration)
     * @param iterations number of timed iterations
     * @param totalUncompressedBytes total uncompressed bytes per iteration
     * @param decompressor the decompression engine to benchmark
     * @return throughput in MB/sec
     * @throws IOException on I/O errors
     */
    private static double benchmarkDecompressionFullIO(
            Path compressedFile,
            Path outputFile,
            int iterations,
            long totalUncompressedBytes,
            BlockDecompressor decompressor)
            throws IOException {
        byte[] outputBuf = new byte[MAX_BGZF_BLOCK_SIZE];

        long startNanos = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            List<CompressedBlock> blocks = readCompressedBlocks(compressedFile);
            try (var out = new BufferedOutputStream(new FileOutputStream(outputFile.toFile()))) {
                for (CompressedBlock block : blocks) {
                    decompressor.decompress(block.data, block.data.length, outputBuf, block.uncompressedSize);
                    out.write(outputBuf, 0, block.uncompressedSize);
                }
            }
        }
        long elapsedNanos = System.nanoTime() - startNanos;

        return toMBps(elapsedNanos, totalUncompressedBytes, iterations);
    }

    // ---- JDK engine ----

    /** Compression engine that uses the standard JDK {@link Deflater} with raw DEFLATE (nowrap). */
    private static final class JdkCompressor implements BlockCompressor {
        private final Deflater deflater;

        /**
         * @param level JDK compression level (0–9)
         */
        JdkCompressor(int level) {
            this.deflater = new Deflater(level, true);
        }

        @Override
        public int compress(byte[] input, int inputLen, byte[] output) {
            deflater.reset();
            deflater.setInput(input, 0, inputLen);
            deflater.finish();
            return deflater.deflate(output, 0, output.length, Deflater.SYNC_FLUSH);
        }
    }

    /** Decompression engine that uses the standard JDK {@link Inflater} with raw DEFLATE (nowrap). */
    private static final class JdkDecompressor implements BlockDecompressor {
        private final Inflater inflater;

        JdkDecompressor() {
            this.inflater = new Inflater(true);
        }

        @Override
        public void decompress(byte[] input, int inputLen, byte[] output, int uncompressedSize) {
            inflater.reset();
            inflater.setInput(input, 0, inputLen);
            try {
                inflater.inflate(output, 0, uncompressedSize);
            } catch (java.util.zip.DataFormatException e) {
                throw new RuntimeException("JDK decompression failed", e);
            }
        }
    }

    // ---- libdeflate byte[] engine ----

    /** Compression engine that uses {@link LibdeflateCompressor} with {@code byte[]} buffers. */
    private static final class LibdeflateByteArrayCompressor implements BlockCompressor {
        private final LibdeflateCompressor compressor;

        /**
         * @param level libdeflate compression level (0–12)
         */
        LibdeflateByteArrayCompressor(int level) {
            this.compressor = new LibdeflateCompressor(level);
        }

        @Override
        public int compress(byte[] input, int inputLen, byte[] output) {
            return compressor.deflateCompress(input, 0, inputLen, output, 0, output.length);
        }
    }

    /** Decompression engine that uses {@link LibdeflateDecompressor} with {@code byte[]} buffers. */
    private static final class LibdeflateByteArrayDecompressor implements BlockDecompressor {
        private final LibdeflateDecompressor decompressor;

        LibdeflateByteArrayDecompressor() {
            this.decompressor = new LibdeflateDecompressor();
        }

        @Override
        public void decompress(byte[] input, int inputLen, byte[] output, int uncompressedSize) {
            decompressor.deflateDecompress(input, 0, inputLen, output, 0, uncompressedSize);
        }
    }
}
