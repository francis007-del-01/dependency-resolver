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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

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

    // --- File operations (Contents API) ---

    public record FileContent(String content, String sha, String path) {}

    public FileContent getFileContent(String owner, String repo, String path, String ref) throws IOException, InterruptedException {
        String url = "%s/repos/%s/%s/contents/%s?ref=%s".formatted(API_BASE, owner, repo, path, ref);
        JsonNode node = get(url);

        String encodedContent = node.get("content").asText().replaceAll("\\s", "");
        String content = new String(Base64.getDecoder().decode(encodedContent), StandardCharsets.UTF_8);
        String sha = node.get("sha").asText();

        return new FileContent(content, sha, path);
    }

    public FileContent getFileContentOrNull(String owner, String repo, String path, String ref) {
        try {
            return getFileContent(owner, repo, path, ref);
        } catch (Exception e) {
            return null;
        }
    }

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

    public List<String> listDirectories(String owner, String repo, String path, String ref) throws IOException, InterruptedException {
        String url = "%s/repos/%s/%s/contents/%s?ref=%s".formatted(API_BASE, owner, repo, path, ref);
        JsonNode node = get(url);

        List<String> dirs = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode entry : node) {
                if ("dir".equals(entry.get("type").asText())) {
                    dirs.add(entry.get("name").asText());
                }
            }
        }
        return dirs;
    }

    public List<String> listDirectoriesOrEmpty(String owner, String repo, String path, String ref) {
        try {
            return listDirectories(owner, repo, path, ref);
        } catch (Exception e) {
            return List.of();
        }
    }

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

    // --- Branch & PR operations ---

    public String getDefaultBranch(String owner, String repo) throws IOException, InterruptedException {
        String url = "%s/repos/%s/%s".formatted(API_BASE, owner, repo);
        JsonNode node = get(url);
        return node.get("default_branch").asText();
    }

    public String getLastCommitter(String owner, String repo, String branch) {
        try {
            String url = "%s/repos/%s/%s/commits/%s".formatted(API_BASE, owner, repo, branch);
            JsonNode node = get(url);
            JsonNode author = node.get("author");
            if (author != null && !author.isNull()) {
                return author.get("login").asText();
            }
        } catch (Exception e) {
            log.debug("Could not get last committer for {}/{}: {}", owner, repo, e.getMessage());
        }
        return null;
    }

    public String getBranchSha(String owner, String repo, String branch) throws IOException, InterruptedException {
        String url = "%s/repos/%s/%s/git/ref/heads/%s".formatted(API_BASE, owner, repo, branch);
        JsonNode node = get(url);
        return node.get("object").get("sha").asText();
    }

    public void createBranch(String owner, String repo, String branchName, String fromSha) throws IOException, InterruptedException {
        String url = "%s/repos/%s/%s/git/refs".formatted(API_BASE, owner, repo);
        ObjectNode body = MAPPER.createObjectNode();
        body.put("ref", "refs/heads/" + branchName);
        body.put("sha", fromSha);
        post(url, body);
        log.info("Created branch {} in {}/{}", branchName, owner, repo);
    }

    public boolean branchExists(String owner, String repo, String branchName) {
        try {
            String url = "%s/repos/%s/%s/git/ref/heads/%s".formatted(API_BASE, owner, repo, branchName);
            get(url);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

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

    public boolean pullRequestExists(String owner, String repo, String head) throws IOException, InterruptedException {
        return findOpenPr(owner, repo, head) != null;
    }

    public PrResult findOpenPr(String owner, String repo, String head) throws IOException, InterruptedException {
        String url = "%s/repos/%s/%s/pulls?head=%s:%s&state=open".formatted(API_BASE, owner, repo, owner, head);
        JsonNode node = get(url);
        if (node.isArray() && !node.isEmpty()) {
            JsonNode pr = node.get(0);
            return new PrResult(pr.get("number").asInt(), pr.get("html_url").asText());
        }
        return null;
    }

    public void updatePullRequest(String owner, String repo, int prNumber, String title, String body) throws IOException, InterruptedException {
        String url = "%s/repos/%s/%s/pulls/%d".formatted(API_BASE, owner, repo, prNumber);
        ObjectNode requestBody = MAPPER.createObjectNode();
        requestBody.put("title", title);
        requestBody.put("body", body);
        patch(url, requestBody);
        log.info("Updated PR #{} in {}/{}", prNumber, owner, repo);
    }

    public void updateBranchRef(String owner, String repo, String branchName, String newSha) throws IOException, InterruptedException {
        String url = "%s/repos/%s/%s/git/refs/heads/%s".formatted(API_BASE, owner, repo, branchName);
        ObjectNode body = MAPPER.createObjectNode();
        body.put("sha", newSha);
        body.put("force", true);
        patch(url, body);
        log.info("Updated branch {} to {} in {}/{}", branchName, newSha.substring(0, 7), owner, repo);
    }

    public record PrResult(int number, String url) {}

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

    private JsonNode patch(String url, ObjectNode body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
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
}
