package com.acme.coderag.indexer.git;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Thin wrapper over the {@code git} binary.
 *
 * <p>Chosen over JGit deliberately. {@code git ls-tree} hands us blob SHAs and
 * sizes for the whole tree in one call, which is precisely the deduplication
 * input we need, and shallow fetching a single commit is one flag. The trade-off
 * is process management, which is contained entirely in this class.
 *
 * <p>Two security properties are maintained here:
 * <ul>
 *   <li><strong>No shell.</strong> Commands are passed as argument arrays, so
 *       nothing in a branch name or URL can be interpreted as shell syntax.</li>
 *   <li><strong>No credential in argv.</strong> The token goes to git through a
 *       generated {@code GIT_ASKPASS} helper reading an environment variable, so
 *       it never appears in {@code ps} output or in a crash log. Only the
 *       username is placed in the URL.</li>
 * </ul>
 */
public class GitClient {

    private static final Logger log = LoggerFactory.getLogger(GitClient.class);

    private static final String PASSWORD_ENV = "CODERAG_GIT_PASSWORD";
    private static final String ASKPASS_SCRIPT = "coderag-askpass.sh";

    private final Duration timeout;

    public GitClient(Duration timeout) {
        this.timeout = timeout == null ? Duration.ofMinutes(10) : timeout;
    }

    /**
     * Shallow-fetches {@code commitSha} into {@code workDir} and checks it out.
     *
     * <p>The exact commit is fetched rather than the branch tip. A pipeline that
     * queues for ten minutes must still index the revision that triggered it, not
     * whatever landed on the branch in the meantime. If the server refuses to
     * serve an arbitrary SHA, we fall back to the branch and then verify.
     *
     * @return the checked-out commit SHA
     */
    public String checkout(Path workDir, String cloneUrl, String commitSha, String branch,
                           String username, String token) {
        try {
            Files.createDirectories(workDir);
            Map<String, String> env = buildEnvironment(workDir, token);
            String url = withUsername(cloneUrl, username);

            run(workDir, env, "git", "init", "-q");
            run(workDir, env, "git", "remote", "add", "origin", url);

            boolean fetchedExactCommit = true;
            try {
                run(workDir, env, "git", "-c", "protocol.version=2",
                        "fetch", "--depth", "1", "--no-tags", "origin", commitSha);
            } catch (GitException e) {
                fetchedExactCommit = false;
                if (branch == null || branch.isBlank()) {
                    throw new GitException("cannot fetch commit " + commitSha
                            + " directly and no branch was supplied as a fallback", e);
                }
                log.warn("Server refused a direct fetch of {}; falling back to branch {}",
                        commitSha, branch);
                run(workDir, env, "git", "-c", "protocol.version=2",
                        "fetch", "--depth", "1", "--no-tags", "origin", branch);
            }

            run(workDir, env, "git", "checkout", "-q", "--detach", "FETCH_HEAD");
            String head = run(workDir, env, "git", "rev-parse", "HEAD").strip();

            if (!matches(head, commitSha)) {
                throw new GitException("expected to index commit " + commitSha
                        + " but the working tree is at " + head
                        + (fetchedExactCommit ? "" : " (branch fallback was used)")
                        + "; refusing to store data under the wrong commit");
            }
            return head;
        } catch (IOException e) {
            throw new GitException("failed to prepare the working tree", e);
        }
    }

    /** Lists every blob at {@code HEAD}, skipping symlinks and submodules. */
    public List<TreeEntry> listTree(Path workDir) {
        // -z keeps paths raw: without it git quotes anything non-ASCII and the
        // path we store stops matching the path the agent asks for.
        String output = run(workDir, Map.of(), "git", "ls-tree", "-r", "-l", "-z", "HEAD");
        List<TreeEntry> entries = new ArrayList<>();
        for (String record : output.split("\0")) {
            if (record.isBlank()) {
                continue;
            }
            int tab = record.indexOf('\t');
            if (tab < 0) {
                continue;
            }
            String[] meta = record.substring(0, tab).trim().split("\\s+");
            String path = record.substring(tab + 1);
            if (meta.length < 4 || !"blob".equals(meta[1])) {
                continue;
            }
            if ("120000".equals(meta[0])) {
                // A symlink's "content" is its target path: nothing to index.
                continue;
            }
            long size;
            try {
                size = Long.parseLong(meta[3]);
            } catch (NumberFormatException e) {
                size = -1;
            }
            entries.add(new TreeEntry(path, meta[2], size));
        }
        return List.copyOf(entries);
    }

    // ── Process plumbing ────────────────────────────────────────────────────

    private String run(Path dir, Map<String, String> extraEnv, String... command) {
        String label = labelOf(command);
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectErrorStream(true);
        builder.environment().putAll(extraEnv);

        Process process = null;
        // Output is drained on a separate thread. Reading inline would block
        // forever on a git that hangs without writing anything, which would make
        // the timeout below decorative -- and a Cloud Run Job that hangs burns
        // its full task timeout before anyone notices.
        try (ExecutorService drain = Executors.newVirtualThreadPerTaskExecutor()) {
            process = builder.start();
            Process started = process;
            Future<String> output = drain.submit(() -> {
                try (InputStream in = started.getInputStream()) {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            });

            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                output.cancel(true);
                throw new GitException("git " + label + " timed out after " + timeout);
            }
            String text = output.get(10, TimeUnit.SECONDS);
            if (process.exitValue() != 0) {
                throw new GitException("git " + label + " failed with exit code "
                        + process.exitValue() + ": " + text.strip());
            }
            return text;
        } catch (IOException e) {
            throw new GitException("could not run git " + label, e);
        } catch (ExecutionException | TimeoutException e) {
            throw new GitException("could not read the output of git " + label, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitException("interrupted while running git " + label, e);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /** First non-flag argument, so error messages say "fetch" and not "-c". */
    private static String labelOf(String[] command) {
        for (int i = 1; i < command.length; i++) {
            if (!command[i].startsWith("-")) {
                return command[i];
            }
        }
        return command.length > 1 ? command[1] : "git";
    }

    private Map<String, String> buildEnvironment(Path workDir, String token) throws IOException {
        Map<String, String> env = new LinkedHashMap<>();
        // Never wait on a terminal: in a batch job that is a hang, not a prompt.
        env.put("GIT_TERMINAL_PROMPT", "0");
        // Ignore any ambient git configuration in the image or home directory.
        env.put("GIT_CONFIG_NOSYSTEM", "1");
        env.put("HOME", workDir.toString());
        if (token != null && !token.isBlank()) {
            env.put("GIT_ASKPASS", writeAskPassHelper(workDir).toString());
            env.put(PASSWORD_ENV, token);
        }
        return env;
    }

    private Path writeAskPassHelper(Path workDir) throws IOException {
        Path script = workDir.resolve(ASKPASS_SCRIPT);
        Files.writeString(script, "#!/bin/sh\nprintf '%s' \"$" + PASSWORD_ENV + "\"\n");
        try {
            Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException e) {
            // Non-POSIX filesystem: best effort.
            if (!script.toFile().setExecutable(true, true)) {
                throw new IOException("could not make the askpass helper executable");
            }
        }
        return script;
    }

    private static String withUsername(String url, String username) {
        if (username == null || username.isBlank()) {
            return url;
        }
        // Only HTTP(S) carries credentials. Injecting a userinfo component into a
        // file:// or ssh:// URL produces something git cannot parse.
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return url;
        }
        int schemeEnd = url.indexOf("://");
        if (schemeEnd < 0) {
            return url;
        }
        String rest = url.substring(schemeEnd + 3);
        if (rest.contains("@")) {
            // Credentials already present; leave the caller's URL alone.
            return url;
        }
        return url.substring(0, schemeEnd + 3)
                + URLEncoder.encode(username, StandardCharsets.UTF_8) + "@" + rest;
    }

    /** Tolerates a short SHA on either side. */
    private static boolean matches(String head, String requested) {
        String a = head.toLowerCase();
        String b = requested.toLowerCase();
        return a.startsWith(b) || b.startsWith(a);
    }
}
