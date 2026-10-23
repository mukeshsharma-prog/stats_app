package com.zenner.datahub.stats.input.web;

/** Publisher-confirmed acceptance; the Writer's database commit is asynchronous. */
public record IngestAcknowledgement(String status, String traceId) {}
