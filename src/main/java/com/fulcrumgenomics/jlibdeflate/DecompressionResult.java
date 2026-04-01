package com.fulcrumgenomics.jlibdeflate;

import java.util.Objects;

/**
 * The result of an extended decompression operation, reporting both the number
 * of compressed input bytes consumed and the number of uncompressed output
 * bytes produced.
 */
public final class DecompressionResult {

    private final int inputBytesConsumed;
    private final int outputBytesProduced;

    public DecompressionResult(int inputBytesConsumed, int outputBytesProduced) {
        this.inputBytesConsumed = inputBytesConsumed;
        this.outputBytesProduced = outputBytesProduced;
    }

    /** Returns the number of compressed input bytes consumed. */
    public int inputBytesConsumed() {
        return inputBytesConsumed;
    }

    /** Returns the number of uncompressed output bytes produced. */
    public int outputBytesProduced() {
        return outputBytesProduced;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DecompressionResult)) return false;
        DecompressionResult that = (DecompressionResult) o;
        return inputBytesConsumed == that.inputBytesConsumed && outputBytesProduced == that.outputBytesProduced;
    }

    @Override
    public int hashCode() {
        return Objects.hash(inputBytesConsumed, outputBytesProduced);
    }

    @Override
    public String toString() {
        return "DecompressionResult{inputBytesConsumed=" + inputBytesConsumed + ", outputBytesProduced="
                + outputBytesProduced + "}";
    }
}
