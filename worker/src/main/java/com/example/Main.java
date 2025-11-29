package com.example;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class Main {

    private static final String WORKER_QUEUE_NAME = "ass1-worker-queue";
    private static final String MANAGER_QUEUE_NAME = "ass1-manager-queue";

    public static void main(String[] args) {
        System.out.println("[WORKER] Starting worker...");

        AWS aws = AWS.getInstance();

        // Make sure bucket and queues exist (idempotent)
        aws.createBucketIfNotExists(aws.bucketName);
        aws.createSqsQueue(WORKER_QUEUE_NAME);
        aws.createSqsQueue(MANAGER_QUEUE_NAME);

        while (true) {
            // 1) Get raw job message from worker queue (e.g. "POS<TAB>https://...txt")
            String jobMessage = aws.receiveOneMessage(WORKER_QUEUE_NAME);

            if (jobMessage == null) {
                System.out.println("[WORKER] No messages. Will check again...");
                sleep(5000);
                continue;
            }

            System.out.println("[WORKER] New job: " + jobMessage);

            try {
                // 2) Parse message: "TYPE<TAB>URL"
                Job job = parseJobMessage(jobMessage);
                System.out.println("[WORKER] Parsed job: type=" + job.analysisType +
                                   ", url=" + job.url);

                // 3) Download text file from URL to a temp file
                Path inputFile = downloadFromUrl(job.url);
                System.out.println("[WORKER] Downloaded input to " + inputFile);

                // 4) (Stub) “analysis” – for now just copy with a header.
                //    Later you’ll replace this with a Stanford Parser helper.
                Path analysisFile = performStubAnalysis(job.analysisType, inputFile);
                System.out.println("[WORKER] Created analysis file " + analysisFile);

                // 5) Upload analysis file to S3
                String outputKey = buildOutputKeyFromUrl(job.url, job.analysisType);
                aws.uploadFile(outputKey, analysisFile);
                String outputS3Url = "s3://" + aws.bucketName + "/" + outputKey;
                System.out.println("[WORKER] Uploaded analysis to " + outputS3Url);

                // 6) Send SUCCESS message to manager:
                //    "<INPUT_URL>\t<OUTPUT_S3_URL>\t<ANALYSIS_TYPE>"
                String resultMessage = job.url + "\t" + outputS3Url + "\t" + job.analysisType;
                aws.sendMessage(MANAGER_QUEUE_NAME, resultMessage);
                System.out.println("[WORKER] Sent result to manager: " + resultMessage);

                // 7) Clean temp files
                deleteQuietly(inputFile);
                deleteQuietly(analysisFile);

            } catch (Exception e) {
                // Any exception during processing this job
                System.err.println("[WORKER] Error while processing job: " + e.getMessage());
                e.printStackTrace();

                // Send ERROR message to manager:
                // "ERROR\t<ORIGINAL_JOB_MESSAGE>\t<SHORT_ERROR>"
                String errorMessage = "ERROR\t" + jobMessage + "\t" +
                        shorten(e.toString(), 200);
                aws.sendMessage(MANAGER_QUEUE_NAME, errorMessage);
                System.out.println("[WORKER] Reported ERROR to manager: " + errorMessage);

                // Then continue to next message (do NOT crash the worker)
            }
        }
    }

    // ---------- Helpers ----------

    // Represents a parsed job request
    private static class Job {
        final String analysisType;
        final String url;

        Job(String analysisType, String url) {
            this.analysisType = analysisType;
            this.url = url;
        }
    }

    // Parse lines like: "POS<TAB>https://www.gutenberg.org/files/1659/1659-0.txt"
    private static Job parseJobMessage(String msg) {
        String[] parts = msg.split("\\t", 2); // split on TAB, into at most 2 parts
        if (parts.length != 2) {
            throw new IllegalArgumentException(
                    "Invalid job format, expected TYPE<TAB>URL but got: " + msg
            );
        }
        String type = parts[0].trim();
        String url = parts[1].trim();
        if (type.isEmpty() || url.isEmpty()) {
            throw new IllegalArgumentException("Empty type or URL in job: " + msg);
        }
        return new Job(type, url);
    }

    // Download a text file from a URL into a temp file.
    // (This is HTTP download; AWS.downloadFile is for S3 objects, so we use standard Java here.)
    private static Path downloadFromUrl(String urlStr) throws IOException {
        Path tempFile = Files.createTempFile("ass1-input-", ".txt");
        URL url = new URL(urlStr);
        try (InputStream in = url.openStream()) {
            Files.copy(in, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return tempFile;
    }

    // Stub analysis: for now just copy the input into another temp file and add a header.
    // Later you will replace this with real Stanford Parser analysis in a helper function.
    private static Path performStubAnalysis(String analysisType, Path inputFile) throws IOException {
        Path outputFile = Files.createTempFile("ass1-analysis-", ".txt");

        String header = "Analysis type: " + analysisType + System.lineSeparator() +
                        "----- Original content below -----" + System.lineSeparator();

        String content = Files.readString(inputFile, StandardCharsets.UTF_8);

        Files.writeString(outputFile, header + content, StandardCharsets.UTF_8);
        return outputFile;
    }

    // Build an S3 key for the analysis file based on the URL and analysis type
    private static String buildOutputKeyFromUrl(String url, String analysisType) {
        // Simple approach: take last part of URL and suffix with analysis type
        // e.g. https://.../1659-0.txt -> analysis/1659-0.txt.POS.analysis.txt
        String filename = url.substring(url.lastIndexOf('/') + 1);
        return "analysis/" + filename + "." + analysisType + ".analysis.txt";
    }

    private static void deleteQuietly(Path p) {
        if (p == null) return;
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            System.out.println("[WORKER] Interrupted, shutting down.");
            Thread.currentThread().interrupt();
        }
    }

    // Shorten long exception strings for error messages
    private static String shorten(String s, int maxLen) {
        if (s == null) return null;
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }
}
