package com.acme.coderag.indexer.config;

import com.acme.coderag.indexer.git.GitClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class GitConfig {

    @Bean
    GitClient gitClient(JobProperties properties) {
        return new GitClient(properties.gitTimeout());
    }
}
