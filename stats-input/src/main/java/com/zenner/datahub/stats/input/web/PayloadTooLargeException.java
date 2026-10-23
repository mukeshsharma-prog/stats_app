package com.zenner.datahub.stats.input.web;

/**
 * Thrown when a chunked request body passes {@code stats.input.limits.max-request-bytes}
 * while it is being read.
 *
 * <p>A request that declares {@code Content-Length} is refused before anything is read.
 * This exception is the other half — a chunked body has no declared length, so the only
 * place the limit can be applied is mid-stream, and the only way out of a
 * {@code ServletInputStream} is to throw. {@link IngestExceptionHandler} turns it back
 * into the 413 the contract promises.</p>
 */
public class PayloadTooLargeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PayloadTooLargeException(long limitBytes) {
        super("Request body exceeded " + limitBytes + " bytes");
    }
}
