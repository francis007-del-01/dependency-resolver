package com.depresolver.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

public class GitHubClient {

    private static final Logger log = LoggerFactory.getLogger(GitHubClient.class);
    private static final String API_BASE = "https://api.github.com";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient httpClient;
    private final String token;

    public GitHubClient(String token) {
        this.token = token;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Fetches file content from a repo. Returns the decoded string content.
     */
    public FileContent getFileContent(String owner, String repo, String path, String ref) throws IOException, InterruptedException {
        String url = "%s/repos/%s/%s/contents/%s?ref=%s".formatted(API_BASE, owner, repo, path, ref);
        JsonNode node = get(url);

        String encodedContent = node.get("content").asText().replaceAll("\\s", "");
        String content = new String(Base64.getDecoder().decode(encodedContent), StandardCharsets.UTF_8);
        String sha = node.get("sha").asText();

        return new FileContent(content, sha, path);
    }

    /**
     * Gets the default branch name for a repo.
     */
    public String getDefaultBranch(String owner, String repo) throws IOException, InterruptedException {
        String url = "%s/repos/%s/%s".formatted(API_BASE, owner, repo);
        JsonNode node = get(url);
        return node.get("default_branch").asText();
    }

    /**
     * Gets the SHA of a branch ref.
     */
    public String getBranchSha(String owner, String repo, String branch) throws IOException, InterruptedException {
        String url = "%s/repos/%s/%s/git/ref/heads/%s".formatted(API_BASE, owner, repo, branch);
        JsonNode node = get(url);
        return node.get("object").get("sha").asText();
    }

    /**
     * Creates a new branch from a given SHA.
     */
    public void createBranch(String owner, String repo, String branchName, String fromSha) throws IOException, InterruptedException {
        String url = "%s/repos/%s/%s/git/refs".formatted(API_BASE, owner, repo);
        ObjectNode body = MAPPER.createObjectNode();
        body.put("ref", "refs/heads/" + branchName);
        body.put("sha", fromSha);
        post(url, body);
        log.info("Created branch {} in {}/{}", branchName, owner, repo);
    }

    /**
     * Checks if a branch exists.
     */
    public boolean branchExists(String owner, String repo, String branchName) {
        try {
            String url = "%s/repos/%s/%s/git/ref/heads/%s".formatted(API_BASE, owner, repo, branchName);
            get(url);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Updates a file on a branch via the Contents API.
     */
    public void updateFile(String owner, String repo, String path, String content,
                           String fileSha, String branch, String commitMessage) throws IOException, InterruptedException {
        String url = "%s/repos/%s/%s/contents/%s".formatted(API_BASE, owner, repo, path);

        ObjectNode body = MAPPER.createObjectNode();
        body.put("message", commitMessage);
        body.put("content", Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8)));
        body.put("sha", fileSha);
        body.put("branch", branch);

        put(url, body);
        log.info("Updated {} on branch {} in {}/{}", path, branch, owner, repo);
    }

    /**
     * Creates a pull request. Returns the PR number and URL.
     */
    public PrResult createPullRequest(String owner, String repo, String title, String body,
                                      String head, String base) throws IOException, InterruptedException {
        String url = "%s/repos/%s/%s/pulls".formatted(API_BASE, owner, repo);

        ObjectNode requestBody = MAPPER.createObjectNode();
        requestBody.put("title", title);
        requestBody.put("body", body);
        requestBody.put("head", head);
        requestBody.put("base", base);

        JsonNode response = post(url, requestBody);
        int prNumber = response.get("number").asInt();
        String prUrl = response.get("html_url").asText();

        log.info("Created PR #{} in {}/{}: {}", prNumber, owner, repo, prUrl);
        return new PrResult(prNumber, prUrl);
    }

    /**
     * Checks if a PR already exists for a given head branch.
     */
    public boolean pullRequestExists(String owner, String repo, String head) throws IOException, InterruptedException {
        String url = "%s/repos/%s/%s/pulls?head=%s:%s&state=open".formatted(API_BASE, owner, repo, owner, head);
        JsonNode node = get(url);
        return node.isArray() && !node.isEmpty();
    }

    // --- HTTP helpers ---

    private JsonNode get(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        checkRateLimit(response);
        checkResponse(response, url);
        return MAPPER.readTree(response.body());
    }

    private JsonNode post(String url, ObjectNode body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        checkRateLimit(response);
        checkResponse(response, url);
        return MAPPER.readTree(response.body());
    }

    private JsonNode put(String url, ObjectNode body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        checkRateLimit(response);
        checkResponse(response, url);
        return MAPPER.readTree(response.body());
    }

    /**
     * Creates or updates a file. If fileSha is null, creates a new file; otherwise updates.
     */
    public void createOrUpdateFile(String owner, String repo, String path, String content,
                                   String fileSha, String branch, String commitMessage) throws IOException, InterruptedException {
        String url = "%s/repos/%s/%s/contents/%s".formatted(API_BASE, owner, repo, path);

        ObjectNode body = MAPPER.createObjectNode();
        body.put("message", commitMessage);
        body.put("content", Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8)));
        body.put("branch", branch);
        if (fileSha != null) {
            body.put("sha", fileSha);
        }

        put(url, body);
        log.info("Created/updated {} on branch {} in {}/{}", path, branch, owner, repo);
    }

    /**
     * Tries to get file content; returns null if not found (404).
     */
    public FileContent getFileContentOrNull(String owner, String repo, String path, String ref) {
        try {
            return getFileContent(owner, repo, path, ref);
        } catch (Exception e) {
            return null;
        }
    }

    private void checkResponse(HttpResponse<String> response, String url) throws IOException {
        int status = response.statusCode();
        if (status == 409) {
            throw new GitHubConflictException("Conflict updating %s: %s".formatted(url, response.body()));
        }
        if (status >= 400) {
            throw new IOException("GitHub API error %d for %s: %s".formatted(status, url, response.body()));
        }
    }

    private void checkRateLimit(HttpResponse<String> response) {
        String remaining = response.headers().firstValue("X-RateLimit-Remaining").orElse(null);
        if (remaining != null) {
            int rem = Integer.parseInt(remaining);
            if (rem < 100) {
                log.warn("GitHub API rate limit low: {} remaining", rem);
            }
            if (rem <= 0) {
                String resetEpoch = response.headers().firstValue("X-RateLimit-Reset").orElse("0");
                long resetAt = Long.parseLong(resetEpoch);
                long waitSeconds = resetAt - System.currentTimeMillis() / 1000;
                log.error("GitHub API rate limit exhausted. Resets in {} seconds", waitSeconds);
            }
        }
    }

    // --- Value types ---

    public record FileContent(String content, String sha, String path) {}

    public record PrResult(int number, String url) {}
}
