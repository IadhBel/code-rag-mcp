package com.acme.coderag.indexer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point for the indexing job.
 *
 * <p>This is a batch process, not a server: it indexes one commit of one
 * repository and exits. Run as a Cloud Run Job so it lives inside the VPC next to
 * Cloud SQL, which means no GitHub runner ever needs database credentials or
 * network access to the database -- the pipeline only holds permission to trigger
 * an execution.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class IndexerApplication {

    public static void main(String[] args) {
        // Exit code must reflect the outcome so the pipeline fails visibly.
        System.exit(SpringApplication.exit(SpringApplication.run(IndexerApplication.class, args)));
    }
}
