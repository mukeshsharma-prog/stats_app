package com.zenner.datahub.stats.input.web;

/** Publisher-confirmed acceptance; the Writer's database commit is asynchronous (DL-163). */
public record IngestAcknowledgement(String status, String traceId) {}
